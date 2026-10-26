package wowchat.commands

import com.typesafe.scalalogging.StrictLogging
import wowchat.game.{GamePackets, GameResources, GuildInfo, GuildMember}

import scala.collection.mutable

// A player lookup from /menu. The game answers later, and the answer goes to reply.
case class WhoRequest(playerName: String, reply: java.util.function.Consumer[String])
case class WhoResponse(playerName: String, guildName: String, lvl: Int, cls: String, race: String, gender: Option[String], zone: String)

object CommandHandler extends StrictLogging {

  // Turns a /who result, or the guild roster when the player is offline, into lines for the menu
  def handleWhoResponse(whoResponse: Option[WhoResponse],
                        guildInfo: GuildInfo,
                        guildRoster: mutable.Map[Long, GuildMember],
                        guildRosterMatcherFunc: GuildMember => Boolean,
                        discordGuild: Option[net.dv8tion.jda.api.entities.Guild] = None): Iterable[String] = {
    
    // Helper to get Discord owner info
    def getDiscordOwner(charName: String): String = {
      discordGuild.flatMap { guild =>
        val member = wowchat.discord.GuildDataCache.getInstance().getMember(charName)
        if (member == null) None
        else {
          val discordId = wowchat.discord.DiscordIdExtractor.extractDiscordId(member)
          if (discordId == null) None
          else {
            Option(guild.getMemberById(discordId)).map { member =>
              s" (Owned by <@$discordId>)"
            }
          }
        }
      }.getOrElse("")
    }
    
    whoResponse.map(r => {
      val owner = getDiscordOwner(r.playerName)
      val rankStr = if (r.guildName.nonEmpty) {
        guildRoster.values.find(_.name.equalsIgnoreCase(r.playerName))
          .flatMap(m => Option(guildInfo).flatMap(_.ranks.get(m.rankIndex)))
          .fold("")(rank => s" [$rank]")
      } else ""
      Seq(s"${r.playerName} ${if (r.guildName.nonEmpty) s"<${r.guildName}>$rankStr " else ""}is a level ${r.lvl}${r.gender.fold(" ")(g => s" $g ")}${r.race} ${r.cls} currently in ${r.zone}.$owner")
    }).getOrElse({
      // Check guild roster
      guildRoster
        .values
        .filter(guildRosterMatcherFunc)
        .map(guildMember => {
          val cls = new GamePackets{}.Classes.valueOf(guildMember.charClass)
          val days = guildMember.lastLogoff.toInt
          val hours = ((guildMember.lastLogoff * 24) % 24).toInt
          val minutes = ((guildMember.lastLogoff * 24 * 60) % 60).toInt
          val minutesStr = s" $minutes minute${if (minutes != 1) "s" else ""}"
          val hoursStr = if (hours > 0) s" $hours hour${if (hours != 1) "s" else ""}," else ""
          val daysStr = if (days > 0) s" $days day${if (days != 1) "s" else ""}," else ""

          val guildNameStr = if (guildInfo != null) {
            val rank = guildInfo.ranks.get(guildMember.rankIndex).fold("")(r => s" [$r]")
            s" <${guildInfo.name}>$rank"
          } else {
            ""
          }

          val owner = getDiscordOwner(guildMember.name)
          s"${guildMember.name}$guildNameStr is a level ${guildMember.level} $cls currently offline. " +
            s"Last seen$daysStr$hoursStr$minutesStr ago in ${GameResources.AREA.getOrElse(guildMember.zoneId, "Unknown Zone")}.$owner"
        })
    })
  }
}
