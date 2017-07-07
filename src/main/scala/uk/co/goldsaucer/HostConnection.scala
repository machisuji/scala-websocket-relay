package uk.co.goldsaucer

import akka.actor.{Actor, ActorRef, Terminated}
import akka.http.scaladsl.model.ws.TextMessage
import akka.stream.ActorMaterializer

import scala.collection.mutable.{Map => MutableMap}

object HostConnection {
  case class SetOutput(actor: ActorRef)
}

class HostConnection(id: String) extends Actor {
  var output: Option[ActorRef] = None
  implicit val materializer = ActorMaterializer()

  def receive = {
    case HostConnection.SetOutput(actor) =>
      output = Some(actor)

      actor ! TextMessage("session: " + id)
    case msg: TextMessage =>
      msg.textStream.runForeach { text =>
        println("Received message from host: " + text)
        output.foreach(_ ! (TextMessage("Received: " + text)))
      }
    case ClientConnection.Message(clientId, msg) =>
      msg.textStream.runForeach { text =>
        println(s"Received message from client $clientId: " + text)
        output.foreach(_ ! (TextMessage(s"$clientId: " + text)))
      }
  }
}