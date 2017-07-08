package uk.co.goldsaucer

import akka.actor.{Actor, ActorRef, Terminated}
import akka.http.scaladsl.model.ws.TextMessage
import akka.stream.ActorMaterializer

object ClientConnection {
  case class Init(actor: ActorRef)
  case class SetID(id: String)
  case class Message(clientId: String, textMessage: TextMessage)
}

class ClientConnection(val host: ActorRef) extends Actor {
  protected var output: ActorRef = null
  protected var id: String = null

  def receive = {
    case ClientConnection.Init(actor) =>
      output = actor
      host ! HostConnection.Connect
    case ClientConnection.SetID(id) =>
      this.id = id
    case msg: TextMessage =>
      host ! ClientConnection.Message(id, msg) // message sent from client to host
    case HostConnection.Message(msg) =>
      output ! msg // message sent from host to client
  }
}
