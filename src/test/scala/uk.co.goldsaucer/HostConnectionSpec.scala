package uk.co.goldsaucer

import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props, Terminated}
import akka.http.scaladsl.model.ws.TextMessage
import akka.testkit.{ImplicitSender, TestActors, TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import uk.co.goldsaucer.WebSocketRelay.system

import scala.concurrent.duration._

class HostConnectionSpec() extends TestKit(ActorSystem("HostConnectionSpec")) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  private var nextSessionId: Int = 1

  class Tee(target: ActorRef, listener: ActorRef) extends Actor {
    override def receive: Receive = {
      case "STOP!" =>
        target ! PoisonPill
        context.stop(self)
      case msg => {
        listener.forward(msg)
        target.forward(msg)
      }
    }
  }

  case class Session(input: TestProbe, output: TestProbe, actor: ActorRef, id: String) {
    def send(msg: Any): Unit = {
      actor ! msg
      input.expectMsg(msg)
    }
  }

  def createSession: Session = {
    val output = TestProbe()
    val input = TestProbe()
    val sessionId = nextSessionId.toString
    val host = system.actorOf(Props(new HostConnection(sessionId)))
    val tee = system.actorOf(Props(new Tee(host, input.ref)), s"session:$sessionId")

    nextSessionId = nextSessionId + 1

    host ! HostConnection.Init(output.ref)

    output.expectMsg(TextMessage.Strict(s"session: $sessionId"))

    Session(input, output, tee, sessionId)
  }

  def connectClients(num: Int, session: Session): Map[Int, TestProbe] = {
    (1 to num).map { n =>
      val client = TestProbe()

      client.send(session.actor, HostConnection.Connect())

      session.input.expectMsg(HostConnection.Connect(None))
      session.output.expectMsg(TextMessage.Strict(s"connected: $n"))
      client.expectMsg(ClientConnection.SetID(n))

      n -> client
    }
      .toMap
  }

  "A HostConnection" must {
    "send the session ID to the connecting host" in {
      val sessionId = "42"
      val host = system.actorOf(Props(new HostConnection(sessionId)), s"session:$sessionId")

      host ! HostConnection.Init(self)

      expectMsg(TextMessage.Strict("session: 42"))

      host ! PoisonPill
    }

    "assign a client ID to a connecting client" in {
      val Session(_, output, host, _) = createSession
      val client1 = TestProbe()
      val client2 = TestProbe()

      client1.send(host, HostConnection.Connect())
      client2.send(host, HostConnection.Connect())

      output.expectMsg(TextMessage.Strict("connected: 1"))
      output.expectMsg(TextMessage.Strict("connected: 2"))

      client1.expectMsg(ClientConnection.SetID(1))
      client2.expectMsg(ClientConnection.SetID(2))

      host ! "STOP!" // stopping Tee
    }

    "assign a new client ID to a client connecting again without a UUID" in {
      val Session(_, output, host, _) = createSession
      val client = TestProbe()

      client.send(host, HostConnection.Connect())

      output.expectMsg(TextMessage.Strict("connected: 1"))
      client.expectMsg(ClientConnection.SetID(1))

      client.send(host, HostConnection.Connect())

      output.expectMsg(TextMessage.Strict("connected: 2"))
      client.expectMsg(ClientConnection.SetID(2))

      host ! "STOP!" // stopping Tee
    }

    "assign the same clientID to a client connecting again with the same UUID" in {
      val Session(_, output, host, _) = createSession
      val client = TestProbe()

      client.send(host, HostConnection.Connect(Some("42")))

      output.expectMsg(TextMessage.Strict("connected: 1"))
      client.expectMsg(ClientConnection.SetID(1))

      client.send(host, HostConnection.Connect(Some("42")))

      output.expectMsg(TextMessage.Strict("connected: 1"))
      client.expectMsg(ClientConnection.SetID(1))

      host ! "STOP!" // stopping Tee
    }

    "notify the host of a disconnecting client" in {
      val Session(_, output, host, _) = createSession
      val client = TestProbe()

      client.send(host, HostConnection.Connect())

      output.expectMsg(TextMessage.Strict("connected: 1"))
      client.expectMsg(ClientConnection.SetID(1))

      client.ref ! PoisonPill

      output.expectMsg(TextMessage.Strict("disconnected: 1"))

      host ! "STOP!" // stopping Tee
    }

    "support master/slave mode" in {
      val fullSession @ Session(fullInput, fullOutput, full, fullSessionId) = createSession
      val masterSession @ Session(masterInput, masterOutput, master, masterSessionId) = createSession
      val slaveSession @ Session(slaveInput, slaveOutput, slave, slaveSessionId) = createSession

      println("Full: " + fullSessionId)
      println("Master: " + masterSessionId)
      println("Slave: " + slaveSessionId)

      val fullClients = connectClients(6, fullSession) // full session can only take 2 more while 3 try to join later
      val masterClients = connectClients(2, masterSession) // have 2 clients connected to the master
      val slaveClients = connectClients(3, slaveSession) // and 3 clients connected ot the slave

      masterSession.send(TextMessage.Strict("/join-session master"))
      slaveSession.send(TextMessage.Strict("/join-session slave"))

      fullInput.expectMsg(HostConnection.RequestToJoin(slaveSessionId, 3))
      masterInput.expectMsg(HostConnection.RequestToJoin(slaveSessionId, 3))
      slaveInput.expectMsg(HostConnection.RequestToJoin(slaveSessionId, 3)) // broadcast goes to itself too, will be ignored though

      masterOutput.expectMsg(TextMessage.Strict(s"!added-slave: $slaveSessionId"))
      slaveOutput.expectMsg(TextMessage.Strict(s"!set-master: $masterSessionId"))
      fullOutput.expectNoMsg(50.milliseconds)

      master ! TextMessage.Strict("0: hello slaves")
      slaveOutput.expectMsg(TextMessage.Strict("hello slaves"))
      masterOutput.expectNoMsg(50.milliseconds)

      slave ! TextMessage.Strict("0: hello master")
      masterOutput.expectMsg(TextMessage.Strict("hello master"))
      slaveOutput.expectNoMsg(50.milliseconds)

      // as usual a message from a client of the master session should go to output
      masterClients.keys.headOption.foreach { clientId =>
        master ! ClientConnection.Message(clientId, TextMessage.Strict("ping"))
        masterOutput.expectMsg(TextMessage.Strict(s"$clientId: ping"))
      }

      // a message from a client to a slave session should be forwarded to the master's output while also going to the
      // slave's output
      slaveClients.keys.headOption.foreach { clientId =>
        slave ! ClientConnection.Message(clientId, TextMessage.Strict("pong"))

        slaveOutput.expectMsg(TextMessage.Strict(s"$clientId: pong"))
        masterOutput.expectMsg(TextMessage.Strict(s"${2 + clientId}: pong"))
      }

      // a message from the host to a slave client should be forwarded from the master to the client host and sent to
      // the client from there
      slaveClients.keys.headOption.foreach { clientId =>
        master ! TextMessage.Strict(s"${2 + clientId}: hello client")

        slaveClients(clientId).expectMsg(HostConnection.Message(TextMessage.Strict("hello client")))
      }

      slaveClients.keys.headOption.foreach { clientId =>
        slaveClients(clientId).ref ! PoisonPill

        slaveOutput.expectMsg(TextMessage.Strict(s"disconnected: $clientId"))
        masterOutput.expectMsg(TextMessage.Strict(s"disconnected: ${2 + clientId}"))
      }
    }
  }
}