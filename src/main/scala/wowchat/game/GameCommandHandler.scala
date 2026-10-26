package wowchat.game

trait GameCommandHandler {

  def sendMessageToWow(tp: Byte, message: String, target: Option[String])
  def sendNotification(message: String)

  // The answer arrives later, through CommandHandler.whoRequest
  def sendWho(playerName: String): Unit

  def sendGuildPromote(playerName: String): Unit
  def sendGuildDemote(playerName: String): Unit
}
