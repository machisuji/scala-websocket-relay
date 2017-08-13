package uk.co.goldsaucer

import akka.actor.{Actor, ActorRef, PoisonPill, Terminated}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.ActorMaterializer

object HostConnection {
  case class Init(actor: ActorRef)
  case class Connect(uuid: Option[String] = None)
  case class Message(textMessage: TextMessage)

  case class RequestToJoin(slaveSessionId: String, numClients: Int)
  case class OfferToJoin(masterSessionId: String, numClients: Int)
  case class AcceptOffer(slaveSessionId: String, numClients: Int)
  case class DeclineOffer(slaveSessionId: String)
  case class ConfirmJoin(masterSessionId: String)

  val maxClients: Int = sys.env.get("MAX_CLIENTS").map(_.toInt).getOrElse(8)

  case class MasterSession(id: String, actor: ActorRef, secret: String = java.util.UUID.randomUUID().toString)
  case class SlaveSession(id: String, actor: ActorRef, numClients: Int, secret: String = java.util.UUID.randomUUID().toString)

  val ToClient = """(?s)\A(\d+):\s(.+)\Z""".r
  val Connected = """\Aconnected:\s(\d+)\Z""".r
  val Disconnected = """\Adisconnected:\s(\d+)\Z""".r
}

class HostConnection(id: String, private var dummy: Boolean = false) extends Actor {
  import HostConnection.{
    RequestToJoin, OfferToJoin, AcceptOffer, DeclineOffer, ConfirmJoin,
    MasterSession, SlaveSession, ToClient, Connected, Disconnected
  }

  type ID = Int
  type UUID = String

  protected var output: ActorRef = null
  protected var clients: Map[ID, ActorRef] = Map.empty
  protected var clientIdMap: Map[UUID, ID] = Map.empty

  protected var maxLocalClients: Int = HostConnection.maxClients

  protected var masterSession: MasterSession = null

  protected var slaveSessions: IndexedSeq[SlaveSession] = IndexedSeq.empty

  protected var allowMaster: Boolean = false // if false the host only wants to join as a slave, not be the master
  protected var allowSlave: Boolean = false // if false do not try to join other sessions as a slave, only be master

  implicit val materializer = ActorMaterializer()

  def receive = {
    case HostConnection.Init(actor)               => init(actor)
    case HostConnection.Connect(clientId)         => clientConnected(sender(), clientId)
    case Terminated(client)                       => clientDisconnected(client)
    case msg: TextMessage                         => messageFromHost(msg)
    case ClientConnection.Message(clientId, msg)  => messageFromClient(clientId, msg, sender)
    case HostConnection.Message(msg)              => messageFromSession(msg, sender)

    case RequestToJoin(slaveSessionId, numClients) if allowMaster && sender != self => {
      handleJoinRequest(slaveSessionId, numClients, sender) // ignore self from broadcasts
    }
    case OfferToJoin(masterSessionId, numClients) if allowSlave  => joinSession(masterSessionId, sender, numClients)
    case AcceptOffer(slaveSessionId, numClients)  if allowMaster => addSlave(slaveSessionId, sender, numClients)
    case ConfirmJoin(masterSessionId)             if allowSlave  => setMaster(masterSessionId, sender)
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
      val msg = s"connected: $clientId"

      clients = clients + (clientId -> client)

      if (uuid.isDefined && !clientIdMap.contains(uuid.get)) {
        clientIdMap = clientIdMap + (uuid.get -> clientId)
      }

      context.watch(client) // => receive Terminated on disconnect
      client ! ClientConnection.SetID(clientId)
      messageToHost(msg)

      if (isSlave) {
        masterSession.actor ! HostConnection.Message(TextMessage.Strict(msg))
      }
    }

    if (result.isEmpty) {
      client ! HostConnection.Message(TextMessage(
        "error: no slots available, max clients reached"
      ))

      client ! PoisonPill
    }
  }

  def nextClientId: Option[ID] =
    (1 to maxLocalClients)
      .toSet
      .diff(clientIdMap.values.toSet) // get IDs which are still available
      .diff(clients.keySet)
      .toSeq
      .sorted
      .headOption // get smallest one if possible

  def clientDisconnected(client: ActorRef): Unit = {
    println(s"disconnected: $client")
    clients.find(_._2 == client).foreach {
      case (clientId, client) =>
        val msg = s"disconnected: $clientId"

        clients = clients.filterKeys(_ != clientId)

        messageToHost(msg)

        if (isSlave) {
          masterSession.actor ! HostConnection.Message(TextMessage.Strict(msg))
        }
    }

    if (dummy && clients.isEmpty) {
      self ! PoisonPill
    }
  }

  def messageFromHost(msg: TextMessage): Unit = {
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
  def messageFromSession(msg: TextMessage, session: ActorRef): Unit = {
    println(s"$id receives message from other session (${masterSession}): $msg")

    msg.textStream.runForeach {
      case ToClient(id, message) if id.toInt > 0 => messageToHost(s"${translateSlaveClientId(id.toInt, session)}: $message")
      case Connected(id) => messageToHost(s"connected: ${translateSlaveClientId(id.toInt, session)}")
      case Disconnected(id) => messageToHost(s"disconnected: ${translateSlaveClientId(id.toInt, session)}")
      case other => messageToHost(other)
    }
  }

  def translateSlaveClientId(
    clientId: Int,
    session: ActorRef,
    slaves: List[SlaveSession] = slaveSessions.toList,
    offset: Int = clients.size
  ): Int = slaves match {
    case Nil => clientId
    case slave :: tail => {
      if (slave.actor == session) offset + clientId
      else translateSlaveClientId(clientId, session, tail, offset + slave.numClients)
    }
  }

  def lookForSession(master: Boolean = true, slave: Boolean = true): Unit = {
    allowMaster = master
    allowSlave = slave

    if (allowSlave) {
      context.actorSelection(s"/user/session:*") ! RequestToJoin(id, clients.size)
    }

    maxLocalClients = clients.size // don't allow any new clients to join once session joining is active
  }

  def handleJoinRequest(slaveSessionId: String, numClients: Int, sender: ActorRef): Unit = {
    if (acceptJoinRequest(slaveSessionId, numClients)) {
      println(s"$id accepts join request from $slaveSessionId")
      sender ! OfferToJoin(id, numClients)
    } else {
      println(s"$id denies join request from $slaveSessionId")
    }
  }

  def acceptJoinRequest(slaveSessionId: String, numClients: Int): Boolean = {
    clients.size + numClients <= HostConnection.maxClients
  }

  def joinSession(masterSessionId: String, sender: ActorRef, numClients: Int): Unit = {
    println(s"$id received offer to join $masterSessionId")

    if (isSlave || clients.size > numClients) {
      sender ! DeclineOffer(id)
    } else {
      sender ! AcceptOffer(id, clients.size)
    }
  }

  def addSlave(slaveSessionId: String, sender: ActorRef, numClients: Int): Unit = {
    if (!isSlave) { // do not allow slaves to have slaves of their own for now
      val slave = SlaveSession(slaveSessionId, sender, numClients)
      slaveSessions = slaveSessions :+ slave

      sender ! ConfirmJoin(id)

      messageToHost(s"!added-slave: $slaveSessionId")
    }
  }

  def setMaster(masterSessionId: String, sender: ActorRef): Unit = {
    this.masterSession = MasterSession(masterSessionId, sender)

    messageToHost(s"!set-master: $masterSessionId")
  }

  def messageToClient(clientId: Int, msg: String): Unit = {
    if (clients.contains(clientId)) {
      val client = clients(clientId)

      client ! HostConnection.Message(TextMessage(msg))
    } else if (clientId == 0) {
      if (isSlave) {
        println(s"$id sending $msg to master (${masterSession.id})")
        masterSession.actor ! HostConnection.Message(TextMessage(msg))
      }
      if (isMaster) {
        println(s"$id sending $msg to slaves ($slaveSessions.values)")
        slaveSessions.foreach(_.actor ! HostConnection.Message(TextMessage(msg)))
      }
    } else if (clientId >= clients.size && slaveSessions.size > 0) {
      println("sending message to slave client")

      forwardMessageToSlave(clientId, msg)
    } else {
      messageToHost(s"unknown: $clientId")
    }
  }

  def forwardMessageToSlave(
    clientId: Int,
    msg: String,
    slaves: List[SlaveSession] = slaveSessions.toList,
    offset: Int = clients.size
  ): Unit = slaves match {
    case Nil => ()
    case slave :: tail => {
      if (clientId >= offset && clientId < offset + slave.numClients) {
        slave.actor ! TextMessage.Strict(s"${clientId - offset}: $msg")
      } else {
        forwardMessageToSlave(clientId, msg, tail, offset + slave.numClients)
      }
    }
  }

  def messageFromClient(clientId: Int, msg: TextMessage, sender: ActorRef): Unit = {
    msg.textStream.runForeach { text =>
      messageToHost(s"$clientId: $text")

      println("message from client to host >> " + s"$clientId: $text")

      if (isSlave) {
        println("forwarding client message to master: " + s"$clientId: $text")
        masterSession.actor ! HostConnection.Message(TextMessage(s"$clientId: $text"))
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
