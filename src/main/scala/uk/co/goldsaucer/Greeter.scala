/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package uk.co.goldsaucer

import akka.NotUsed
import akka.http.scaladsl.model.ws.BinaryMessage
import akka.stream.scaladsl.Sink

import scala.io.StdIn
import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props, Terminated}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Source}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.UpgradeToWebSocket
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.ContentTypes.`text/html(UTF-8)`
import akka.util.Timeout

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

object Greeter extends App {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  //#websocket-handler
  // The Greeter WebSocket Service expects a "name" per message and
  // returns a greeting message for that name
  val greeterWebSocketService =
    Flow[Message]
      .mapConcat {
        // we match but don't actually consume the text message here,
        // rather we simply stream it back as the tail of the response
        // this means we might start sending the response even before the
        // end of the incoming message has been received
        case tm: TextMessage =>
          TextMessage(tm.textStream.map(_.toUpperCase)) :: Nil
        case bm: BinaryMessage =>
          // ignore binary messages but drain content to avoid the stream being clogged
          bm.dataStream.runWith(Sink.ignore)
          Nil
      }
  //#websocket-handler

  var message: String = "hallo"

  val source = Source
    .tick(5 seconds, 3 second, TextMessage("hallo"))
    .map(_ => TextMessage((new java.util.Date).toString + ": " + message))

  val sink = Sink.foreach[Message] {
    case tm: TextMessage =>
      tm.textStream.runForeach(text => message = text)
    case bm: BinaryMessage =>
      bm.dataStream.runWith(Sink.ignore)
  }

  val flow = Flow.fromSinkAndSource(sink, source)

  val chatRoom = system.actorOf(Props(new ChatRoom), "chat")

  def hostFlow(): Flow[Message, Message, NotUsed] = {
    val sessionId = scala.util.Random.nextInt(99) + 1
    val session = system.actorOf(
      Props(new HostConnection(sessionId.toString)),
      s"session:$sessionId"
    )

    val incomingMessages: Sink[Message, NotUsed] =
      Flow[Message].to(Sink.actorRef[Message](session, PoisonPill))

    val outgoingMessages: Source[Message, NotUsed] =
      Source.actorRef[Message](10, OverflowStrategy.fail)
        .mapMaterializedValue { outActor =>
          session ! HostConnection.SetOutput(outActor)
          NotUsed
        }

    Flow.fromSinkAndSource(incomingMessages, outgoingMessages)
  }

  def clientFlow(sessionId: String): Option[Flow[Message, Message, NotUsed]] = {
    implicit val timeout = Timeout(1 second)
    import scala.concurrent.ExecutionContext.Implicits.global

    val flow = system.actorSelection(s"user/session:$sessionId").resolveOne().map { host =>
      val clientId = scala.util.Random.nextInt(99) + 1
      val client = system.actorOf(
        Props(new ClientConnection(clientId.toString, host)),
        s"client:$clientId"
      )

      val incomingMessages: Sink[Message, NotUsed] =
        Flow[Message].to(Sink.actorRef[Message](client, PoisonPill))

      val outgoingMessages: Source[Message, NotUsed] =
        Source.actorRef[Message](10, OverflowStrategy.fail)
          .mapMaterializedValue { outActor =>
            client ! ClientConnection.SetOutput(outActor)
            NotUsed
          }

      Some(Flow.fromSinkAndSource(incomingMessages, outgoingMessages))
    }

    Await.result(flow.fallbackTo(Future { None }), 1 second)
  }

  //#websocket-request-handling
  val requestHandler: HttpRequest => HttpResponse = {
    case req @ HttpRequest(GET, Uri.Path("/session"), _, _, _) =>
      req.header[UpgradeToWebSocket] match {
        case Some(upgrade) => upgrade.handleMessages(hostFlow())
        case None          => HttpResponse(400, entity = "Not a valid websocket request!")
      }
    case req @ HttpRequest(GET, uri, _, _, _) if uri.path.toString().startsWith("/session/") =>
      println("session: " + uri.path.toString)

      val sessionId = uri.path.toString.substring("/session/".size)

      req.header[UpgradeToWebSocket] match {
        case Some(upgrade) =>
          clientFlow(sessionId)
            .map(flow => upgrade.handleMessages(flow))
            .getOrElse(HttpResponse(404, entity = "Session not found"))
        case None => HttpResponse(400, entity = "Not a valid websocket request!")
      }
    case req @ HttpRequest(GET, Uri.Path("/"), _, _, _) =>
      HttpResponse(
        200,
        entity = HttpEntity(`text/html(UTF-8)`, scala.io.Source.fromFile("client.html").mkString)
      )

    case r: HttpRequest =>
      r.discardEntityBytes() // important to drain incoming HTTP Entity stream
      HttpResponse(404, entity = "Unknown resource!")
  }
  //#websocket-request-handling

  val bindingFuture =
    Http().bindAndHandleSync(requestHandler, interface = "localhost", port = 8080)

  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  StdIn.readLine()

  import system.dispatcher // for the future transformations
  bindingFuture
    .flatMap(_.unbind()) // trigger unbinding from the port
    .onComplete(_ => system.terminate()) // and shutdown when done
}

object ChatRoom {
  case object Join
  case class ChatMessage(message: String)
}

class ChatRoom extends Actor {
  import ChatRoom._
  var users: Set[ActorRef] = Set.empty

  def receive = {
    case Join =>
      users += sender()
      // we also would like to remove the user when its actor is stopped
      context.watch(sender())

    case Terminated(user) =>
      users -= user

    case msg: ChatMessage =>
      users.foreach(_ ! msg)
  }
}

object User {
  case class Connected(out: ActorRef)

  case class IncomingMessage(text: String)
  case class OutgoingMessage(text: String)
}

class User(chatRoom: ActorRef) extends Actor {
  import User._

  def receive = {
    case Connected(outgoing) =>
      context.become(connected(outgoing))
  }

  def connected(outgoing: ActorRef): Receive = {
    chatRoom ! ChatRoom.Join

    {
      case IncomingMessage(text) =>
        chatRoom ! ChatRoom.ChatMessage(text)

      case ChatRoom.ChatMessage(text) =>
        outgoing ! OutgoingMessage(text)
    }
  }

}
