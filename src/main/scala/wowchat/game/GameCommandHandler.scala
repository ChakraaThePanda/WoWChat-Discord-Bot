package wowchat.game

trait GameCommandHandler {

  def sendMessageToWow(tp: Byte, message: String, target: Option[String])

  // The answer arrives later, through request.reply
  def sendWho(request: wowchat.commands.WhoRequest): Unit

  def sendGuildPromote(playerName: String): Unit
  def sendGuildDemote(playerName: String): Unit
}
