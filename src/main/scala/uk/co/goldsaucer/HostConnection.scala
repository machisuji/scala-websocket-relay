package uk.co.goldsaucer

import akka.actor.{Actor, ActorRef, PoisonPill, Terminated}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.ActorMaterializer

object HostConnection {
  case class Init(actor: ActorRef)
  case class Connect(uuid: Option[String] = None)
  case class Message(textMessage: TextMessage)

  val maxClients: Int = 16
}

class HostConnection(id: String, private var dummy: Boolean = false) extends Actor {

  type ID = Int
  type UUID = String

  protected var output: ActorRef = null
  protected var clients: Map[ID, ActorRef] = Map.empty
  protected var clientIdMap: Map[UUID, ID] = Map.empty

  implicit val materializer = ActorMaterializer()

  def receive = {
    case HostConnection.Init(actor)               => init(actor)
    case HostConnection.Connect(clientId)         => clientConnected(sender(), clientId)
    case Terminated(client)                       => clientDisconnected(client)
    case msg: TextMessage                         => messageFromHost(msg)
    case ClientConnection.Message(clientId, msg)  => messageFromClient(clientId, msg, sender)
  }

  def init(actor: ActorRef): Unit = {
    output = actor

    actor ! TextMessage("session: " + id)
  }

  def clientConnected(client: ActorRef, uuid: Option[String]): Unit = {
    val result: Option[Unit] = nextClientId.map { clientId =>
      clients = clients + (clientId -> client)

      if (uuid.isDefined) {
        clientIdMap = clientIdMap + (uuid.get -> clientId)
      }

      context.watch(client) // => receive Terminated on disconnect
      client ! ClientConnection.SetID(clientId)
      messageToHost(s"connected: $clientId")
    }

    if (result.isEmpty) {
      client ! HostConnection.Message(TextMessage(
        "error: no slots available, max clients reached"
      ))

      client ! PoisonPill
    }
  }

  def nextClientId: Option[ID] =
    (1 to HostConnection.maxClients)
      .toSet
      .diff(clientIdMap.values.toSet) // get IDs which are still available
      .toSeq
      .sorted
      .headOption // get smallest one if possible

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
      val ToClient = """\A(\d+):\s(.+)\Z""".r

      text match {
        case ToClient(id, message) => messageToClient(id.toInt, message)
        case _ => messageToHost("invalid")
      }
    }
  }

  def messageToClient(clientId: Int, msg: String): Unit = {
    if (clients.contains(clientId)) {
      val client = clients(clientId)

      client ! HostConnection.Message(TextMessage(msg))
    } else {
      messageToHost(s"unknown: $clientId")
    }
  }

  def messageFromClient(clientId: Int, msg: TextMessage, sender: ActorRef): Unit = {
    msg.textStream.runForeach { text =>
      if (text.startsWith("ClientId: ")) {
        val id = clientId
        val uuid = text.substring(text.indexOf(":") + 1).trim()
        lazy val setId: Option[ID] = nextClientId

        if (clientIdMap.contains(uuid)) {
          sender ! ClientConnection.SetID(clientIdMap(uuid))

          messageToHost(s"${clientIdMap(uuid)}: $text")
        } else if (clientIdMap.size < HostConnection.maxClients && setId.isDefined) {
          val clientId = setId.get

          clientIdMap = clientIdMap + (uuid -> clientId)

          sender ! ClientConnection.SetID(clientId)

          messageToHost(s"$clientId: $text")
        } else {
          sender ! HostConnection.Message(TextMessage("error: maximum number of clients reached"))
          sender ! PoisonPill
        }
      } else {
        messageToHost(s"$clientId: $text")
      }
    }
  }

  def messageToHost(msg: String): Unit = messageToHost(TextMessage(msg))

  def messageToHost(msg: Message): Unit = {
    if (dummy) println(s"[dummy-$id] message for host: $msg")
    else output ! msg
  }
}
