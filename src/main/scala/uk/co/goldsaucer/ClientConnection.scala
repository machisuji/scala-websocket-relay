package uk.co.goldsaucer

import akka.actor.{Actor, ActorRef, Terminated}
import akka.http.scaladsl.model.ws.TextMessage
import akka.stream.ActorMaterializer

object ClientConnection {
  case class Init(actor: ActorRef)
  case class SetID(id: Int)
  case class Message(clientId: Int, textMessage: TextMessage)
}

class ClientConnection(val host: ActorRef) extends Actor {
  protected var output: ActorRef = null
  protected var id: Int = -1

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
