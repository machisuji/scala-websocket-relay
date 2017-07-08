package uk.co.goldsaucer

import akka.actor.{Actor, ActorRef, Terminated}
import akka.http.scaladsl.model.ws.TextMessage
import akka.stream.ActorMaterializer

object ClientConnection {
  case class Init(actor: ActorRef)
  case class Message(clientId: String, textMessage: TextMessage)
}

class ClientConnection(val id: String, val host: ActorRef) extends Actor {
  protected var output: ActorRef = null

  def receive = {
    case ClientConnection.Init(actor) =>
      output = actor
      host ! HostConnection.Connect(id)
    case msg: TextMessage =>
      host ! ClientConnection.Message(id, msg) // message sent from client to host
    case HostConnection.Message(msg) =>
      output ! msg // message sent from host to client
  }
}
