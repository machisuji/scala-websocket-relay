package uk.co.goldsaucer

import akka.actor.{Actor, ActorRef, PoisonPill, Terminated}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.ActorMaterializer

object HostConnection {
  case class Init(actor: ActorRef)
  case class Connect(uuid: Option[String] = None)
  case class Message(textMessage: TextMessage)

  case class RequestToJoin(slaveSessionId: String, numClients: Int)
  case class OfferToJoin(masterSessionId: String)
  case class AcceptOffer(slaveSessionId: String)
  case class DeclineOffer(slaveSessionId: String)
  case class ConfirmJoin(masterSessionId: String)

  val maxClients: Int = sys.env.get("MAX_CLIENTS").map(_.toInt).getOrElse(8)
}

class HostConnection(id: String, private var dummy: Boolean = false) extends Actor {
  import HostConnection.{RequestToJoin, maxClients, OfferToJoin, AcceptOffer, DeclineOffer, ConfirmJoin}

  type ID = Int
  type UUID = String

  protected var output: ActorRef = null
  protected var clients: Map[ID, ActorRef] = Map.empty
  protected var clientIdMap: Map[UUID, ID] = Map.empty

  protected var masterSessionId: String = null
  protected var masterSession: ActorRef = null

  protected var slaveSessions: Map[String, ActorRef] = Map.empty

  protected var allowMaster: Boolean = false // if false the host only wants to join as a slave, not be the master
  protected var allowSlave: Boolean = false // if false do not try to join other sessions as a slave, only be master

  implicit val materializer = ActorMaterializer()

  def receive = {
    case HostConnection.Init(actor)               => init(actor)
    case HostConnection.Connect(clientId)         => clientConnected(sender(), clientId)
    case Terminated(client)                       => clientDisconnected(client)
    case msg: TextMessage                         => messageFromHost(msg)
    case ClientConnection.Message(clientId, msg)  => messageFromClient(clientId, msg, sender)
    case HostConnection.Message(msg)              => messageFromSession(msg)

    case RequestToJoin(slaveSessionId, numClients) if allowMaster && sender != self => {
      handleJoinRequest(slaveSessionId, numClients, sender) // ignore self from broadcasts
    }
    case OfferToJoin(masterSessionId: String) if allowSlave => joinSession(masterSessionId, sender)
    case AcceptOffer(slaveSessionId)          if allowMaster  => addSlave(slaveSessionId, sender)
    case ConfirmJoin(masterSessionId)         if allowSlave => setMaster(masterSessionId, sender)
  }

  def init(actor: ActorRef): Unit = {
    output = actor

    actor ! TextMessage("session: " + id)
  }

  def wantsToJoin: Boolean = allowMaster || allowSlave

  def isMaster: Boolean = slaveSessions.nonEmpty
  def isSlave: Boolean = masterSession ne null

  def clientConnected(client: ActorRef, uuid: Option[String]): Unit = {
    val result: Option[Unit] = uuid.flatMap(clientIdMap.get).orElse(nextClientId).map { clientId =>
      clients = clients + (clientId -> client)

      if (uuid.isDefined && !clientIdMap.contains(uuid.get)) {
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
      .diff(clients.keySet)
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
    val ToClient = """\A(\d+):\s(.+)\Z""".r

    msg.textStream.runForeach {
      case ToClient(id, message) => messageToClient(id.toInt, message)
      case "/join-session" => lookForSession()
      case "/join-session master" => lookForSession(slave = false)
      case "/join-session slave" => lookForSession(master = false)
      case _ => messageToHost("invalid")
    }
  }

  /**
    * A message coming from another HostConnection session. I.e. a slave or master.
    */
  def messageFromSession(msg: TextMessage): Unit = {
    println(s"$id receives message from other session ($masterSessionId): $msg")

    val ToClient = """\A(\d+):\s(.+)\Z""".r

    msg.textStream.runForeach {
      case ToClient(id, message) if id.toInt > 0 => messageToHost(s"${clients.size + id.toInt}: $message")
      case other => messageToHost(other)
    }
  }

  def lookForSession(master: Boolean = true, slave: Boolean = true): Unit = {
    allowMaster = master
    allowSlave = slave

    if (allowSlave) {
      context.actorSelection(s"/user/session:*") ! RequestToJoin(id, clients.size)
    }
  }

  def handleJoinRequest(slaveSessionId: String, numClients: Int, sender: ActorRef): Unit = {
    if (acceptJoinRequest(slaveSessionId, numClients)) {
      println(s"$id accepts join request from $slaveSessionId")
      sender ! OfferToJoin(id)
    } else {
      println(s"$id denies join request from $slaveSessionId")
    }
  }

  def acceptJoinRequest(slaveSessionId: String, numClients: Int): Boolean = {
    clients.size + numClients <= maxClients
  }

  def joinSession(masterSessionId: String, sender: ActorRef): Unit = {
    println(s"$id received offer to join $masterSessionId")

    if (this.masterSessionId ne null) {
      sender ! DeclineOffer(id)
    } else {
      sender ! AcceptOffer(id)
    }
  }

  def addSlave(slaveSessionId: String, sender: ActorRef): Unit = {
    if (!isSlave) { // do not allow slaves to have slaves of their own for now
      slaveSessions = slaveSessions + (slaveSessionId -> sender)

      sender ! ConfirmJoin(id)

      messageToHost(s"!added-slave: $slaveSessionId")
    }
  }

  def setMaster(masterSessionId: String, sender: ActorRef): Unit = {
    this.masterSessionId = masterSessionId
    this.masterSession = sender

    messageToHost(s"!set-master: $masterSessionId")
  }

  def messageToClient(clientId: Int, msg: String): Unit = {
    if (clients.contains(clientId)) {
      val client = clients(clientId)

      client ! HostConnection.Message(TextMessage(msg))
    } else if (clientId == 0) {
      if (masterSession ne null) {
        println(s"$id sending $msg to master ($masterSessionId)")
        masterSession ! HostConnection.Message(TextMessage(s"0: $msg"))
      }
      println(s"$id sending $msg to slaves ($slaveSessions.values)")
      slaveSessions.values.foreach(_ ! HostConnection.Message(TextMessage(s"0: $msg")))
    } else {
      messageToHost(s"unknown: $clientId")
    }
  }

  def messageFromClient(clientId: Int, msg: TextMessage, sender: ActorRef): Unit = {
    msg.textStream.runForeach { text =>
      messageToHost(s"$clientId: $text")

      if (masterSession ne null) {
        masterSession ! HostConnection.Message(TextMessage(s"$clientId: $text"))
      }
    }
  }

  def messageToHost(msg: String): Unit = messageToHost(TextMessage(msg))

  def messageToHost(msg: Message): Unit = {
    if (dummy) println(s"[dummy-$id] message for host: $msg")
    else {
      output ! msg
    }
  }
}
