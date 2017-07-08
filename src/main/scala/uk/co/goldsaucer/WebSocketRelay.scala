/*
 * Copyright (C) 2017 Goldsaucer Ltd <http://goldsaucer.co.uk>
 */

package uk.co.goldsaucer

import akka.NotUsed
import akka.stream.scaladsl.Sink

import akka.actor.{ActorSystem, PoisonPill, Props}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Source}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.UpgradeToWebSocket
import akka.http.scaladsl.model.ws.{Message}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.ContentTypes.`text/html(UTF-8)`
import akka.util.Timeout

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

object WebSocketRelay extends App {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

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
          session ! HostConnection.Init(outActor)
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
            client ! ClientConnection.Init(outActor)
            NotUsed
          }

      Some(Flow.fromSinkAndSource(incomingMessages, outgoingMessages))
    }

    Await.result(flow.fallbackTo(Future { None }), 1 second)
  }

  val requestHandler: HttpRequest => HttpResponse = {
    case req @ HttpRequest(GET, Uri.Path("/session"), _, _, _) =>
      req.header[UpgradeToWebSocket] match {
        case Some(upgrade) => upgrade.handleMessages(hostFlow())
        case None          => HttpResponse(400, entity = "Not a valid websocket request!")
      }
    case req @ HttpRequest(GET, uri, _, _, _) if uri.path.toString().startsWith("/session/") =>
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
      r.discardEntityBytes()
      HttpResponse(404, entity = "not found")
  }

  val serverBinding = Http().bindAndHandleSync(requestHandler, interface = "0.0.0.0", port = 8080)

  def shutdown(): Unit = {
    import system.dispatcher // for the future transformations
    serverBinding
      .flatMap(_.unbind())
      .onComplete { _ =>
        system.terminate()
        println("Relay stopped.")
      }
  }

  println(s"WebSocket Relay online at http://0.0.0.0:8080/")

  sys.addShutdownHook(shutdown)
}
