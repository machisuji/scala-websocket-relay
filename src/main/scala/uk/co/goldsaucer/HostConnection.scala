package uk.co.goldsaucer

import akka.actor.{Actor, ActorRef, PoisonPill, Terminated}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.ActorMaterializer

object HostConnection {
  case class Init(actor: ActorRef)
  case object Connect
  case class Message(textMessage: TextMessage)
}

class HostConnection(id: String, private var dummy: Boolean = false) extends Actor {
  protected var output: ActorRef = null
  protected var clients: Map[String, ActorRef] = Map.empty

  implicit val materializer = ActorMaterializer()

  def receive = {
    case HostConnection.Init(actor)               => init(actor)
    case HostConnection.Connect                   => clientConnected(sender())
    case Terminated(client)                       => clientDisconnected(client)
    case msg: TextMessage                         => messageFromHost(msg)
    case ClientConnection.Message(clientId, msg)  => messageFromClient(clientId, msg)
  }

  def init(actor: ActorRef): Unit = {
    output = actor

    actor ! TextMessage("session: " + id)
  }

  def clientConnected(client: ActorRef): Unit = {
    val clientId = (clients.size + 1).toString
    clients = clients + (clientId -> client)
    context.watch(client) // => receive Terminated on disconnect
    client ! ClientConnection.SetID(clientId)
    messageToHost(s"connected: $clientId")
  }

  def clientDisconnected(client: ActorRef): Unit = {
    clients.find(_._2 == client).foreach { case (clientId, client) =>
      clients = clients.filterKeys(_ != clientId)
      messageToHost(s"disconnected: $clientId")
    }

    if (dummy && clients.isEmpty) {
      self ! PoisonPill
    }
  }

  def messageFromHost(msg: TextMessage): Unit = {
    msg.textStream.runForeach { text =>
      val ToClient = """\A([a-f0-9]+):\s(.+)\Z""".r

      text match {
        case ToClient(id, message) => messageToClient(id, message)
        case _ => messageToHost("invalid")
      }
    }
  }

  def messageToClient(clientId: String, msg: String): Unit = {
    if (clients.contains(clientId)) {
      val client = clients(clientId)

      client ! HostConnection.Message(TextMessage(msg))
    } else {
      messageToHost(s"unknown: $clientId")
    }
  }

  def messageFromClient(clientId: String, msg: TextMessage): Unit = {
    msg.textStream.runForeach { text =>
      messageToHost(s"$clientId: $text")
    }
  }

  def messageToHost(msg: String): Unit = messageToHost(TextMessage(msg))

  def messageToHost(msg: Message): Unit = {
    if (dummy) println(s"[dummy-$id] message for host: $msg")
    else output ! msg
  }
}
