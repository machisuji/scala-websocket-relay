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

  Log.level = Log.Level.Debug

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
      val otherSession @ Session(otherInput, otherOutput, other, otherSessionId) = createSession

      println("Full: " + fullSessionId)
      println("Master: " + masterSessionId)
      println("Slave: " + slaveSessionId)
      println("Other: " + otherSessionId)

      val fullClients = connectClients(6, fullSession) // full session can only take 2 more while 3 try to join later
      val masterClients = connectClients(2, masterSession) // have 2 clients connected to the master
      val slaveClients = connectClients(3, slaveSession) // and 3 clients connected ot the slave
      val otherClients = connectClients(1, otherSession)

      masterSession.send(TextMessage.Strict("/join-session master"))
      slaveSession.send(TextMessage.Strict("/join-session slave"))

      fullInput.expectMsg(HostConnection.RequestToJoin(slaveSessionId, slaveClients.size))
      masterInput.expectMsg(HostConnection.RequestToJoin(slaveSessionId, slaveClients.size))
      slaveInput.expectMsg(HostConnection.RequestToJoin(slaveSessionId, slaveClients.size)) // broadcast goes to itself too, will be ignored though
      otherInput.expectMsg(HostConnection.RequestToJoin(slaveSessionId, slaveClients.size))

      otherSession.send(TextMessage.Strict("/join-session slave"))

      fullInput.expectMsg(HostConnection.RequestToJoin(otherSessionId, otherClients.size))
      masterInput.expectMsg(HostConnection.RequestToJoin(otherSessionId, otherClients.size))
      otherInput.expectMsg(HostConnection.RequestToJoin(otherSessionId, otherClients.size)) // broadcast goes to itself too, will be ignored though

      masterOutput.expectMsg(TextMessage.Strict(s"!added-slave: $slaveSessionId"))
      masterOutput.expectMsg(TextMessage.Strict(s"!added-slave: $otherSessionId"))
      slaveOutput.expectMsg(TextMessage.Strict(s"!set-master: $masterSessionId"))
      otherOutput.expectMsg(TextMessage.Strict(s"!set-master: $masterSessionId"))
      fullOutput.expectNoMsg(50.milliseconds)

      master ! TextMessage.Strict("0: hello slaves")
      slaveOutput.expectMsg(TextMessage.Strict("hello slaves"))
      otherOutput.expectMsg(TextMessage.Strict("hello slaves"))
      masterOutput.expectNoMsg(50.milliseconds)

      slave ! TextMessage.Strict("0: hello\nmaster")
      masterOutput.expectMsg(TextMessage.Strict("hello\nmaster"))
      otherOutput.expectMsg(TextMessage.Strict("hello\nmaster"))
      slaveOutput.expectNoMsg(50.milliseconds)

      // big messages should be supported
      val bigMessage = "M:QuestionsMessage;+++{\"Questions\":[{\"t\":\"Who is spending more money per head on helping people stop smoking despite the fact the number of quitters is falling?\",\"a\":[{\"c\":false,\"n\":1,\"t\":\"The BBC\",\"a\":\"/audios/58648/download\"},{\"c\":false,\"n\":2,\"t\":\"The FSA\",\"a\":\"/audios/62446/download\"},{\"c\":false,\"n\":3,\"t\":\"The WWF\",\"a\":\"/audios/62447/download\"},{\"c\":true,\"n\":4,\"t\":\"The NHS\",\"a\":\"/audios/62448/download\"}],\"b\":\"/audios/62445/download\",\"m\":\"\"},{\"t\":\"In athletics, how is the \\\"hop, step and jump\\\" normally known?\",\"a\":[{\"c\":false,\"n\":1,\"t\":\"High jump\",\"a\":\"/audios/52159/download\"},{\"c\":true,\"n\":2,\"t\":\"Triple jump\",\"a\":\"/audios/52160/download\"},{\"c\":false,\"n\":3,\"t\":\"Long jump\",\"a\":\"/audios/52161/download\"},{\"c\":false,\"n\":4,\"t\":\"Steeplechase\",\"a\":\"/audios/52162/download\"}],\"b\":\"/audios/52158/download\",\"m\":\"\"},{\"t\":\"Which businessman has donated 400,000 to Labour according to a party spokesman?\",\"a\":[{\"c\":false,\"n\":1,\"t\":\"Richard Branson\",\"a\":\"/audios/44905/download\"},{\"c\":true,\"n\":2,\"t\":\"Alan Sugar\",\"a\":\"/audios/44904/download\"},{\"c\":false,\"n\":3,\"t\":\"Stelios Haji-Ioannou\",\"a\":\"/audios/65057/download\"},{\"c\":false,\"n\":4,\"t\":\"Theo Paphitis\",\"a\":\"/audios/65058/download\"}],\"b\":\"/audios/65056/download\",\"m\":\"\"}]}"
      slave ! TextMessage.Strict(s"0: $bigMessage")
      masterOutput.expectMsg(TextMessage.Strict(bigMessage))
      otherOutput.expectMsg(TextMessage.Strict(bigMessage))

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