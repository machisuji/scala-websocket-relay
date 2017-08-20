package uk.co.goldsaucer

import akka.actor.{Actor, ActorRef, PoisonPill, Terminated}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.ActorMaterializer
import uk.co.goldsaucer.HostConnection.RejectTakeOver

object HostConnection {
  case class Init(actor: ActorRef)
  case class Connect(uuid: Option[String] = None)
  case class Message(textMessage: TextMessage)
  case class Broadcast(textMessage: TextMessage)

  case class TakeOver(secret: String)
  case object AcceptTakeOver
  case object RejectTakeOver

  case class RequestToJoin(slaveSessionId: String, numClients: Int)
  case class OfferToJoin(masterSessionId: String, numClients: Int)
  case class AcceptOffer(slaveSessionId: String, numClients: Int)
  case class DeclineOffer(slaveSessionId: String)
  case class ConfirmJoin(masterSessionId: String)

  val maxClients: Int = sys.env.get("MAX_CLIENTS").map(_.toInt).getOrElse(8)

  case class MasterSession(id: String, actor: ActorRef)
  case class SlaveSession(id: String, actor: ActorRef, numClients: Int, secret: String = java.util.UUID.randomUUID().toString)

  val ToClient = """(?s)\A(\d+):\s(.+)\Z""".r
  val Connected = """\Aconnected:\s(\d+)\Z""".r
  val Disconnected = """\Adisconnected:\s(\d+)\Z""".r
  val MasterLeft = """\A!master-left\Z""".r
  val SlaveLeft = """\A!slave-left: ([\w\-]+)\Z""".r
}

class HostConnection(
  id: String,
  private var dummy: Boolean = false,
  val secret: String = java.util.UUID.randomUUID().toString
) extends Actor with Logs {
  import HostConnection.{
    RequestToJoin, OfferToJoin, AcceptOffer, DeclineOffer, ConfirmJoin,
    MasterSession, SlaveSession, ToClient, Connected, Disconnected, TakeOver,
    MasterLeft, SlaveLeft
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
    case TakeOver(secret)                         => handleTakeOver(secret, sender)
    case HostConnection.Connect(clientId)         => clientConnected(sender(), clientId)
    case Terminated(client)                       => clientDisconnected(client)
    case msg: TextMessage                         => messageFromHost(msg)
    case ClientConnection.Message(clientId, msg)  => messageFromClient(clientId, msg, sender)
    case HostConnection.Message(msg)              => messageFromSession(msg, sender)
    case HostConnection.Broadcast(msg)            => broadcastFromSession(msg, sender)

    case RequestToJoin(slaveSessionId, numClients) if allowMaster && sender != self => {
      handleJoinRequest(slaveSessionId, numClients, sender) // ignore self from broadcasts
    }
    case OfferToJoin(masterSessionId, numClients) if allowSlave  => joinSession(masterSessionId, sender, numClients)
    case AcceptOffer(slaveSessionId, numClients)  if allowMaster => addSlave(slaveSessionId, sender, numClients)
    case ConfirmJoin(masterSessionId)             if allowSlave  => setMaster(masterSessionId, sender)

    case stats: StatsTracker.Stats => showStats(stats)
  }

  def init(actor: ActorRef): Unit = {
    output = actor

    actor ! TextMessage("session: " + id)
    actor ! TextMessage("secret: " + secret)

    StatsTracker.actorDo { _ ! StatsTracker.HostConnected }
  }

  def handleTakeOver(secret: String, sender: ActorRef): Unit = {
    if (secret == this.secret) {
      log.info(s"take-over of $id using secret $secret")
      sender ! AcceptOffer
    } else {
      log.info(s"failed take-over attempt of $id using secret $secret")
      sender ! RejectTakeOver
    }
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
    log.info(s"disconnected: $client")
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
      case "/close-session" => closeSession()
      case "/leave-session" => leaveSession()
      case "/stats" => StatsTracker.actorDo { _ ! StatsTracker.RequestStats }
      case _ => messageToHost("invalid")
    }
  }

  /**
    * A message coming from another HostConnection session. I.e. a slave or master.
    */
  def messageFromSession(msg: TextMessage, session: ActorRef, output: String => Unit = messageToHost): Unit = {
    log.debug(s"$id receives message from other session (${masterSession}): $msg")

    msg.textStream.runForeach {
      case ToClient(id, message) if id.toInt > 0 => output(s"${translateSlaveClientId(id.toInt, session)}: $message")
      case Connected(id) => output(s"connected: ${translateSlaveClientId(id.toInt, session)}")
      case Disconnected(id) => output(s"disconnected: ${translateSlaveClientId(id.toInt, session)}")
      case MasterLeft() => masterLeft(output)
      case SlaveLeft(sessionId) => slaveLeft(sessionId, output)
      case other => output(other)
    }
  }

  def broadcastFromSession(msg: TextMessage, session: ActorRef): Unit = {
    log.debug(s"$id broadcasting message from other session (${masterSession}): $msg")

    def broadcast(message: String): Unit = {
      messageToHost(message)
      slaveSessions.filterNot(_.actor == session).foreach(_.actor ! HostConnection.Message(msg))
    }

    messageFromSession(msg, session, output = broadcast)
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

    StatsTracker.actorDo { _ ! StatsTracker.HostLookingForSession }

    maxLocalClients = clients.size // don't allow any new clients to join once session joining is active
  }

  /**
    * Close the session not allowing it to join or be joined by any other sessions.
    */
  def closeSession(): Unit = {
    allowMaster = false
    allowSlave = false
  }

  def leaveSession(): Unit = {
    if (isMaster) {
      slaveSessions.foreach(_.actor ! HostConnection.Message(TextMessage.Strict("!master-left")))

      slaveSessions = IndexedSeq.empty
    } else if (isSlave) {
      masterSession.actor ! HostConnection.Message(TextMessage.Strict(s"!slave-left: $id"))

      masterSession = null
    }

    stopLookingForSession()
  }

  def masterLeft(output: String => Unit): Unit = {
    masterSession = null

    stopLookingForSession()

    output("!master-left")
  }

  def slaveLeft(sessionId: String, output: String => Unit): Unit = {
    slaveSessions = slaveSessions.filter(_.id != sessionId)

    stopLookingForSession()

    output(s"!slave-left $sessionId")
  }

  def stopLookingForSession(): Unit = {
    allowMaster = false
    allowSlave = false
    maxLocalClients = HostConnection.maxClients

    StatsTracker.actorDo { _ ! StatsTracker.HostStoppedLookingForSession }
  }

  def handleJoinRequest(slaveSessionId: String, numClients: Int, sender: ActorRef): Unit = {
    if (acceptJoinRequest(slaveSessionId, numClients)) {
      log.debug(s"$id accepts join request from $slaveSessionId")
      sender ! OfferToJoin(id, numClients)
    } else {
      log.debug(s"$id denies join request from $slaveSessionId")
    }
  }

  def acceptJoinRequest(slaveSessionId: String, numClients: Int): Boolean = {
    clients.size + numClients <= HostConnection.maxClients
  }

  def joinSession(masterSessionId: String, sender: ActorRef, numClients: Int): Unit = {
    log.debug(s"$id received offer to join $masterSessionId")

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
        log.debug(s"$id sending $msg to master (${masterSession.id})")
        masterSession.actor ! HostConnection.Broadcast(TextMessage(msg))
      }
      if (isMaster) {
        log.debug(s"$id sending $msg to slaves ($slaveSessions.values)")
        slaveSessions.foreach(_.actor ! HostConnection.Message(TextMessage(msg)))
      }
    } else if (clientId >= clients.size && slaveSessions.size > 0) {
      log.debug(s"$id sending $msg to slave client $clientId")

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

      log.debug("message from client to host >> " + s"$clientId: $text")

      if (isSlave) {
        log.debug("forwarding client message to master: " + s"$clientId: $text")
        masterSession.actor ! HostConnection.Message(TextMessage(s"$clientId: $text"))
      }
    }
  }

  def messageToHost(msg: String): Unit = messageToHost(TextMessage(msg))

  def messageToHost(msg: Message): Unit = {
    if (dummy) log.info(s"[dummy-$id] message for host: $msg")
    else {
      output ! msg
    }
  }

  def showStats(stats: StatsTracker.Stats): Unit = {
    val message =
      s"""
        | {
        |   "numHostsOnline": ${stats.numHostsOnline},
        |   "numHostsLookingForSession": ${stats.numHostsLookingToJoinSession}
        | }
      """.stripMargin

    output ! TextMessage.Strict(message)
  }
}
