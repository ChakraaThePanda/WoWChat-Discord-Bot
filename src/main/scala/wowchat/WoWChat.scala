package wowchat

import java.util.concurrent.{Executors, TimeUnit}

import wowchat.common.{CommonConnectionCallback, Global, ReconnectDelay, WowChatConfig}
import wowchat.discord.Discord
import wowchat.game.GameConnector
import wowchat.realm.{RealmConnectionCallback, RealmConnector}
import com.typesafe.scalalogging.StrictLogging
import io.netty.channel.nio.NioEventLoopGroup

object WoWChat extends StrictLogging {

  // The build date, e.g. 2026.10.26 (set when the jar is built)
  private val RELEASE = Option(getClass.getPackage.getImplementationVersion).getOrElse("dev build")

  // Tells the watchdog not to restart: the same mistake would just happen again
  val SETUP_ERROR_EXIT_CODE = 2

  def stopForSetupError(message: String): Nothing = {
    logger.error(message)
    logger.error("Fix wowchat.conf, then start the bot again.")
    sys.exit(SETUP_ERROR_EXIT_CODE)
  }

  def main(args: Array[String]): Unit = {
    logger.info(s"Running WoWChat - version $RELEASE")
    val confFile = if (args.nonEmpty) {
      args(0)
    } else {
      logger.info("No configuration file supplied. Trying with default wowchat.conf.")
      "wowchat.conf"
    }
    // The Discord features read the config through this, so they use the same file
    System.setProperty("wowchat.configFile", confFile)
    Global.config = try {
      WowChatConfig(confFile)
    } catch {
      case e: Exception => stopForSetupError(s"Could not read $confFile: ${e.getMessage}")
    }

    val gameConnectionController: CommonConnectionCallback = new CommonConnectionCallback {

      private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor
      private val reconnectDelay = new ReconnectDelay

      override def connect: Unit = {
        Global.group = new NioEventLoopGroup

        val realmConnector = new RealmConnector(new RealmConnectionCallback {
          override def success(host: String, port: Int, realmName: String, realmId: Int, sessionKey: Array[Byte]): Unit = {
            gameConnect(host, port, realmName, realmId, sessionKey)
          }

          override def disconnected: Unit = doReconnect

          override def error: Unit = sys.exit(1)
        })

        realmConnector.connect
      }

      private def gameConnect(host: String, port: Int, realmName: String, realmId: Int, sessionKey: Array[Byte]): Unit = {
        new GameConnector(host, port, realmName, realmId, sessionKey, this).connect
      }

      override def connected: Unit = reconnectDelay.reset

      override def disconnected: Unit = doReconnect

      def doReconnect: Unit = {
        Global.group.shutdownGracefully()
        Global.discord.changeRealmStatus("Connecting...")
        val delay = reconnectDelay.getNext
        logger.info(s"Disconnected from server! Reconnecting in $delay seconds...")

        reconnectExecutor.schedule(new Runnable {
          override def run(): Unit = connect
        }, delay, TimeUnit.SECONDS)
      }
    }

    logger.info("Connecting to Discord...")
    Global.discord = try new Discord(new CommonConnectionCallback {
      override def connected: Unit = {
        wowchat.discord.DiscordIdExtractor.initialize()
        
        wowchat.discord.GuildOnlineListPublisher.init()
        wowchat.discord.GuildStatusRotation.init()
        wowchat.discord.GuildRoleSync.init()
        wowchat.discord.GuildRosterPublisher.init()
        wowchat.discord.DiscordDMHandler.init()
        wowchat.discord.AutoGuildInviteHandler.init()
        wowchat.discord.IgnoreManager.init()
        wowchat.discord.BanManager.init()
        gameConnectionController.connect
      }

      override def error: Unit = sys.exit(1)
    }) catch {
      case _: net.dv8tion.jda.api.exceptions.InvalidTokenException =>
        stopForSetupError("The Discord bot token in wowchat.conf is invalid. Copy it again from the Discord Developer Portal (your app -> Bot -> Reset Token).")
    }
  }
}
