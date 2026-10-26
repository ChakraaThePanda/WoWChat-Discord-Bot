package wowchat.discord

import wowchat.common._
import com.typesafe.scalalogging.StrictLogging
import com.vdurmont.emoji.EmojiParser
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.JDA.Status
import net.dv8tion.jda.api.entities.{Activity, MessageType}
import net.dv8tion.jda.api.entities.Activity.ActivityType
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.events.StatusChangeEvent
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.{ButtonInteractionEvent, StringSelectInteractionEvent}
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.session.ShutdownEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.{CloseCode, GatewayIntent}
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.cache.CacheFlag
import wowchat.game.GamePackets

import scala.collection.JavaConverters._
import scala.collection.mutable

object Discord {

  def sendMessage(channel: MessageChannel, message: String): Unit = {
    splitUpByLength(message, 2000).foreach { chunk =>
      channel.sendMessage(chunk).queue(
        _ => (), // Success callback (do nothing)
        error => {
          System.err.println(s"[Discord] Failed to send message to ${channel.getName}: ${error.getMessage}")
        }
      )
    }
  }

  def sendMessageSuppressMentions(channel: MessageChannel, message: String): Unit = {
    splitUpByLength(message, 2000).foreach { chunk =>
      channel.sendMessage(chunk)
        .setAllowedMentions(java.util.Collections.emptyList())
        .queue(
          _ => (),
          error => {
            System.err.println(s"[Discord] Failed to send message to ${channel.getName}: ${error.getMessage}")
          }
        )
    }
  }

  private def splitUpByLength(message: String, maxLength: Int): Seq[String] = {
    val retArr = mutable.ArrayBuffer.empty[String]

    var tmp = message
    while (tmp.length > maxLength) {
      val subStr = tmp.substring(0, maxLength)
      val spaceIndex = subStr.lastIndexOf(' ')
      tmp = if (spaceIndex == -1) {
        retArr += subStr
        tmp.substring(maxLength)
      } else {
        retArr += subStr.substring(0, spaceIndex)
        tmp.substring(spaceIndex + 1)
      }
    }

    if (tmp.nonEmpty) {
      retArr += tmp
    }

    retArr
  }

  private def splitUpMessageToWow(format: String, name: String, message: String): Seq[String] = {
    val maxTmpLen = 255 - format
      .replace("%time", Global.getTime)
      .replace("%user", name)
      .replace("%message", "")
      .length

    splitUpByLength(message, maxTmpLen)
      .map(message => {
        val formatted = format
          .replace("%time", Global.getTime)
          .replace("%user", name)
          .replace("%message", message)

        // If the final formatted message is a dot command, it should be disabled. Add a space in front.
        if (formatted.startsWith(".")) {
          s" $formatted"
        } else {
          formatted
        }
      })
  }
}

class Discord(discordConnectionCallback: CommonConnectionCallback) extends ListenerAdapter
  with GamePackets with StrictLogging {

  private val jda = JDABuilder
    .createDefault(Global.config.discord.token,
      GatewayIntent.GUILD_EXPRESSIONS,
      GatewayIntent.GUILD_MEMBERS,
      GatewayIntent.GUILD_MESSAGES,
      GatewayIntent.GUILD_PRESENCES,
      GatewayIntent.MESSAGE_CONTENT,
      GatewayIntent.DIRECT_MESSAGES)
    .setMemberCachePolicy(MemberCachePolicy.ALL)
    .disableCache(CacheFlag.SCHEDULED_EVENTS,
      CacheFlag.VOICE_STATE)
    .addEventListeners(this)
    .build

  private val messageResolver = MessageResolver(jda)

  private var lastStatus: Option[Activity] = None
  private var firstConnect = true

  // Guild death role ping - loaded once from conf
  private val guildDeathRoleId: Option[String] = {
    try {
      val conf = com.typesafe.config.ConfigFactory.parseFile(
        new java.io.File(System.getProperty("wowchat.configFile", "wowchat.conf")))
        .resolve(com.typesafe.config.ConfigResolveOptions.defaults().setAllowUnresolved(true))
      
      // Check if feature is enabled (NEW path with fallback to OLD behavior)
      val enabled = if (conf.hasPath("guildDeathNotifications.enabled")) {
        conf.getBoolean("guildDeathNotifications.enabled")
      } else if (conf.hasPath("guildDeathRoleId")) {
        true // Old config exists, assume enabled
      } else {
        false
      }
      
      if (!enabled) {
        None
      } else {
        // Try NEW path first, fall back to OLD
        val roleIdPath = if (conf.hasPath("guildDeathNotifications.roleId")) {
          "guildDeathNotifications.roleId"
        } else {
          "guildDeathRoleId"
        }
        
        if (conf.hasPath(roleIdPath)) {
          val v = conf.getString(roleIdPath).trim
          if (wowchat.discord.ConfigHelper.isId(v)) Some(v) else None
        } else None
      }
    } catch { case _: Throwable => None }
  }

  // What counts as a death: guildDeathNotifications.patterns, regexes whose first ( ) is
  // the player's name. Servers word death messages differently, so these can be changed.
  private val guildDeathPatterns: Seq[java.util.regex.Pattern] = {
    val defaults = Seq(
      """\[Hardcore Death\] (\S+)""",
      """^(\S+) the level \d+ .+ has fallen to""",
      """^(\S+) has fallen at level""",
      """^(\S+) has been slain by"""
    )
    try {
      val conf = com.typesafe.config.ConfigFactory.parseFile(
        new java.io.File(System.getProperty("wowchat.configFile", "wowchat.conf")))
        .resolve(com.typesafe.config.ConfigResolveOptions.defaults().setAllowUnresolved(true))
      val patterns = if (conf.hasPath("guildDeathNotifications.patterns")) {
        conf.getStringList("guildDeathNotifications.patterns").asScala
      } else {
        defaults
      }
      patterns.filter(_.trim.nonEmpty).flatMap(p => {
        try {
          Some(java.util.regex.Pattern.compile(p))
        } catch {
          case e: Throwable =>
            logger.error(s"Death ping pattern isn't a valid regex, skipping it: $p (${e.getMessage})")
            None
        }
      })
    } catch { case _: Throwable => defaults.map(java.util.regex.Pattern.compile) }
  }

  // The guild member a relayed message announces the death of, if any
  private def deadGuildMember(message: String): Option[String] = {
    guildDeathPatterns.iterator.map(_.matcher(message)).collectFirst {
      case m if m.find() && m.groupCount() >= 1 && Option(m.group(1)).exists(isGuildMember) => m.group(1)
    }
  }

  private def isGuildMember(name: String): Boolean = Global.game.exists {
    case handler: wowchat.game.GamePacketHandler =>
      handler.getGuildRoster.valuesIterator.exists(_.name.equalsIgnoreCase(name))
    case _ => false
  }

  def changeStatus(gameType: ActivityType, message: String): Unit = {
    lastStatus = Some(Activity.of(gameType, message))
    jda.getPresence.setActivity(lastStatus.get)
  }

  def changeGuildStatus(message: String): Unit = {
    changeStatus(ActivityType.WATCHING, message)
  }

  def changeRealmStatus(message: String): Unit = {
    changeStatus(ActivityType.CUSTOM_STATUS, message)
  }

  def sendMessageFromWow(from: Option[String], message: String, wowType: Byte, wowChannel: Option[String]): Unit = {
    Global.wowToDiscord.get((wowType, wowChannel.map(_.toLowerCase))).foreach(discordChannels => {
      // Strip server-injected prefixes (e.g. "{?" or "[HC] " added by some private servers)
      val cleanMessage = if (message.startsWith("{?")) {
        message.substring(2)
      } else if (message.startsWith("[HC] ")) {
        message.substring(5)
      } else {
        message
      }
      val parsedLinks = messageResolver.resolveEmojis(messageResolver.stripColorCoding(messageResolver.resolveLinks(cleanMessage)))

      discordChannels.foreach {
        case (channel, channelConfig) =>
          try {
          val errors = mutable.ArrayBuffer.empty[String]
          val parsedResolvedTags = from.map(_ => {
            messageResolver.resolveTags(channel, parsedLinks, errors += _)
          })
            .getOrElse(parsedLinks)
            .replace("`", "\\`")
            .replace("*", "\\*")
            .replace("_", "\\_")
            .replace("~", "\\~")

          val (userDisplay, hasDiscordTag) = if (channelConfig.showTagInDiscord) {
            from.map { charName =>
              Global.game.flatMap {
                case handler: wowchat.game.GamePacketHandler =>
                  handler.getGuildRoster.valuesIterator
                    .find(_.name.equalsIgnoreCase(charName))
                    .flatMap(member => Option(DiscordIdExtractor.extractDiscordId(member)))
                    .map(discordId => (s"<@$discordId>/$charName", true))
                case _ => None
              }.getOrElse((charName, false))
            }.getOrElse(("", false))
          } else {
            (from.getOrElse(""), false)
          }

          val formatted = channelConfig
            .format
            .replace("%time", Global.getTime)
            .replace("%user", userDisplay)
            .replace("%message", parsedResolvedTags)
            .replace("%target", wowChannel.getOrElse(""))

          // Death ping: mention the role when the message announces a guild member's death
          val finalMessage = guildDeathRoleId match {
            case Some(roleId) if deadGuildMember(parsedLinks).isDefined => formatted + s" <@&$roleId>"
            case _ => formatted
          }

          val filter = shouldFilter(channelConfig.filters, finalMessage)
          logger.info(s"${if (filter) "FILTERED " else ""}WoW->Discord(${channel.getName}) $finalMessage")
          if (!filter) {
            if (hasDiscordTag) {
              Discord.sendMessageSuppressMentions(channel, finalMessage)
            } else {
              Discord.sendMessage(channel, finalMessage)
            }
          }
          if (Global.config.discord.enableTagFailedNotifications) {
            errors.foreach(error => {
              Global.game.foreach(_.sendMessageToWow(ChatEvents.CHAT_MSG_WHISPER, error, from))
              Discord.sendMessage(channel, error)
            })
          }
          } catch {
            case e: Throwable =>
              System.err.println(s"[Discord] Skipping channel ${channel.getName} due to error: ${e.getMessage}")
          }
      }
    })
  }

  def sendGuildNotification(eventKey: String, message: String): Unit = {
    Global.guildEventsToDiscord
      .getOrElse(eventKey, Global.wowToDiscord.getOrElse(
          (ChatEvents.CHAT_MSG_GUILD, None), mutable.Set.empty
        ).map(_._1)
      )
      .foreach(channel => {
        logger.info(s"WoW->Discord(${channel.getName}) $message")
        Discord.sendMessage(channel, message)
      })
  }

  def sendAchievementNotification(name: String, achievementId: Int): Unit = {
    val notificationConfig = Global.config.guildConfig.notificationConfigs("achievement")
    if (!notificationConfig.enabled) {
      return
    }

    Global.wowToDiscord.get((ChatEvents.CHAT_MSG_GUILD, None))
      .foreach(_.foreach {
        case (discordChannel, channelConfig) =>
          val formatted = notificationConfig
            .format
            .replace("%time", Global.getTime)
            .replace("%user", name)
            .replace("%achievement", messageResolver.resolveAchievementId(achievementId))

          val filter = shouldFilter(channelConfig.filters, formatted)
          logger.info(s"${if (filter) "FILTERED " else ""}WoW->Discord(${discordChannel.getName}) $formatted")
          if (!filter) {
            Discord.sendMessage(discordChannel, formatted)
          }
      })
  }

  override def onStatusChange(event: StatusChangeEvent): Unit = {
    event.getNewStatus match {
      case Status.CONNECTED =>
        lastStatus.foreach(game => changeStatus(game.getType, game.getName))
        // this is a race condition if already connected to wow, reconnect to discord, and bot tries to send
        // wow->discord message. alternatively it was throwing already garbage collected exceptions if trying
        // to use the previous connection's channel references. I guess need to refill these maps on discord reconnection
        Global.discordToWow.clear
        Global.wowToDiscord.clear
        Global.guildEventsToDiscord.clear

        // getNext seq of needed channels from config
        val configChannels = Global.config.channels.map(channelConfig => {
          channelConfig.discord.channel.toLowerCase -> channelConfig
        })
        val configChannelsNames = configChannels.map(_._1)

        val discordTextChannels = event.getEntity.getTextChannels.asScala
        val eligibleDiscordChannels = discordTextChannels
          .filter(channel =>
            configChannelsNames.contains(channel.getName.toLowerCase) ||
            configChannelsNames.contains(channel.getId)
          )

        // build directional maps
        eligibleDiscordChannels.foreach(channel => {
          configChannels
            .filter {
              case (name, _) =>
                name.equalsIgnoreCase(channel.getName) ||
                name == channel.getId
            }
            .foreach {
              case (name, channelConfig) =>
                if (channelConfig.chatDirection == ChatDirection.both ||
                  channelConfig.chatDirection == ChatDirection.discord_to_wow) {
                  Global.discordToWow.addBinding(
                    name.toLowerCase, channelConfig.wow
                  )
                }

                if (channelConfig.chatDirection == ChatDirection.both ||
                  channelConfig.chatDirection == ChatDirection.wow_to_discord) {
                  Global.wowToDiscord.addBinding(
                    (channelConfig.wow.tp, channelConfig.wow.channel.map(_.toLowerCase)),
                    (channel, channelConfig.discord)
                  )
                }
            }
          })

        // build guild notification maps
        val guildEventChannels = Global.config.guildConfig.notificationConfigs
          .filter {
            case (_, notificationConfig) =>
              notificationConfig.enabled
          }
          .flatMap {
            case (key, notificationConfig) =>
              notificationConfig.channel.map(key -> _)
          }

        discordTextChannels.foreach(channel => {
          guildEventChannels
            .filter {
              case (_, name) =>
                name.equalsIgnoreCase(channel.getName) ||
                name == channel.getId
            }
            .foreach {
              case (notificationKey, _) =>
                Global.guildEventsToDiscord.addBinding(notificationKey, channel)
            }
        })

        // Chat relay is optional: with no channels the bot still runs, for /menu and the panels
        val foundChannels = discordTextChannels.flatMap(c => Seq(c.getName.toLowerCase, c.getId)).toSet
        if (firstConnect) {
          configChannelsNames.filterNot(foundChannels.contains).foreach(name =>
            logger.warn(s"Chat channel $name from wowchat.conf wasn't found in your Discord server, or the bot can't see it. Its chat won't be relayed.")
          )
          if (Global.discordToWow.isEmpty && Global.wowToDiscord.isEmpty) {
            logger.info("No chat channels set up: running as a menu-only bot (/menu and /help).")
          }
        }

        if (firstConnect) {
          discordConnectionCallback.connected
          firstConnect = false
          // Register slash commands on first connect only
          MenuHandler.registerCommands(jda)
        } else {
          discordConnectionCallback.reconnected
        }
      case Status.DISCONNECTED =>
        discordConnectionCallback.disconnected
      case _ =>
    }
  }

  override def onShutdown(event: ShutdownEvent): Unit = {
    event.getCloseCode match {
      case CloseCode.DISALLOWED_INTENTS =>
        wowchat.WoWChat.stopForSetupError("Turn on PRESENCE INTENT, SERVER MEMBERS INTENT and MESSAGE CONTENT INTENT for this bot in the Discord Developer Portal (your app -> Bot -> Privileged Gateway Intents).")
      case CloseCode.AUTHENTICATION_FAILED =>
        wowchat.WoWChat.stopForSetupError("The Discord bot token in wowchat.conf is invalid. Copy it again from the Discord Developer Portal (your app -> Bot -> Reset Token).")
      case _ =>
    }
  }

  // /menu, /help and everything inside the menu (buttons, select menus, forms) live in MenuHandler
  override def onSlashCommandInteraction(event: SlashCommandInteractionEvent): Unit = {
    MenuHandler.handleSlashCommand(event)
  }

  override def onButtonInteraction(event: ButtonInteractionEvent): Unit = {
    MenuHandler.handleButton(event)
  }

  override def onStringSelectInteraction(event: StringSelectInteractionEvent): Unit = {
    MenuHandler.handleSelect(event)
  }

  override def onModalInteraction(event: ModalInteractionEvent): Unit = {
    MenuHandler.handleModal(event)
  }

  override def onMessageReceived(event: MessageReceivedEvent): Unit = {
    // ignore messages received from self
    if (event.getAuthor.getIdLong == jda.getSelfUser.getIdLong) {
      return
    }

    // ignore messages from non-text channels
    if (event.getChannelType != ChannelType.TEXT) {
      return
    }

    // ignore non-default messages
    val messageType = event.getMessage.getType
    if (messageType != MessageType.DEFAULT && messageType != MessageType.INLINE_REPLY) {
      return
    }

    val channel = event.getChannel
    val channelId = channel.getId
    val channelName = event.getChannel.getName.toLowerCase
    val effectiveName = sanitizeName(event.getMember.getEffectiveName)
    val message = (sanitizeMessage(event.getMessage.getContentDisplay) +: event.getMessage.getAttachments.asScala.map(_.getUrl))
      .filter(_.nonEmpty)
      .mkString(" ")
    logger.debug(s"RECV DISCORD MESSAGE: [${channel.getName}] [$effectiveName]: $message")
    if (message.isEmpty) {
      logger.error(s"Received a message in channel ${channel.getName} but the content was empty. You likely forgot to enable MESSAGE CONTENT INTENT for your bot in the Discord Developers portal.")
    }

    // send to all configured wow channels
    Global.discordToWow
      .get(channelName)
      .fold(Global.discordToWow.get(channelId))(Some(_))
      .foreach(_.foreach(channelConfig => {
        val finalMessages = if (channelConfig.showUsernameInWow) {
          Discord.splitUpMessageToWow(channelConfig.format, effectiveName, message)
        } else {
          Discord.splitUpMessageToWow("%message", "", message)
        }

        finalMessages.foreach(finalMessage => {
          val filter = shouldFilter(channelConfig.filters, finalMessage)
          logger.info(s"${if (filter) "FILTERED " else ""}Discord->WoW(${
            channelConfig.channel.getOrElse(ChatEvents.valueOf(channelConfig.tp))
          }) $finalMessage")
          if (!filter) {
            Global.game.fold(logger.error("Cannot send message! Not connected to WoW!"))(handler => {
              handler.sendMessageToWow(channelConfig.tp, finalMessage, channelConfig.channel)
            })
          }
        })
      }))
  }

  def shouldFilter(filtersConfig: Option[FiltersConfig], message: String): Boolean = {
    filtersConfig
      .fold(Global.config.filters)(Some(_))
      .exists(filters => filters.enabled && filters.patterns.exists(message.matches))
  }

  def sanitizeName(name: String): String = {
    name.replace("|", "||")
  }

  def sanitizeMessage(message: String): String = {
    EmojiParser.parseToAliases(message, EmojiParser.FitzpatrickAction.REMOVE)
  }

}
