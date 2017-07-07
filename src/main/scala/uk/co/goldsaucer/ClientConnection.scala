package uk.co.goldsaucer

import akka.actor.{Actor, ActorRef, Terminated}
import akka.http.scaladsl.model.ws.TextMessage
import akka.stream.ActorMaterializer

object ClientConnection {
  case class SetOutput(actor: ActorRef)
  case class Message(clientId: String, textMessage: TextMessage)
}

class ClientConnection(val id: String, val host: ActorRef) extends Actor {
  var output: Option[ActorRef] = None
  implicit val materializer = ActorMaterializer()

  def receive = {
    case ClientConnection.SetOutput(actor) =>
      output = Some(actor)
    case msg: TextMessage =>
      host ! ClientConnection.Message(id, msg)
  }
}