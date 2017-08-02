/*
 * Copyright (C) 2017 Goldsaucer Ltd <http://goldsaucer.co.uk>
 */

package uk.co.goldsaucer

import java.io.File

import akka.NotUsed
import akka.stream.scaladsl.Sink
import akka.actor.{ActorSystem, PoisonPill, Props}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Source}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage, UpgradeToWebSocket}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.ContentTypes.`text/html(UTF-8)`
import akka.http.scaladsl.model.Uri.Query
import akka.util.Timeout

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

object WebSocketRelay extends App {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  def hostFlow(sessionId: Option[String] = None, keepAlive: Option[String] = None): Flow[Message, Message, NotUsed] = {
    val id = sessionId.getOrElse(java.util.UUID.randomUUID.toString)
    val session = system.actorOf(
      Props(new HostConnection(id)),
      s"session:$id"
    )

    val incomingMessages: Sink[Message, NotUsed] =
      Flow[Message].to(Sink.actorRef[Message](session, PoisonPill))

    val outgoingMessages: Source[Message, NotUsed] =
      Source.actorRef[Message](10, OverflowStrategy.fail)
        .mapMaterializedValue { outActor =>
          session ! HostConnection.Init(outActor)
          NotUsed
        }

    val flow = Flow.fromSinkAndSource(incomingMessages, outgoingMessages)

    keepAlive
      .map(message => flow.keepAlive(30 seconds, () => TextMessage(message)))
      .getOrElse(flow)
  }

  def clientFlow(
    sessionId: String,
    keepAlive: Option[String] = None,
    dummyFallback: Boolean = false,
    clientUUID: Option[String]
  ): Option[Flow[Message, Message, NotUsed]] = {
    implicit val timeout = Timeout(1 second)
    import scala.concurrent.ExecutionContext.Implicits.global

    val _session = system
      .actorSelection(s"user/session:$sessionId")
      .resolveOne()

    val session =
      if (dummyFallback)
        _session.fallbackTo(Future {
          system.actorOf(Props(new HostConnection(sessionId, dummy = true)), s"session:$sessionId")
        })
      else
        _session

    val flow = session.map { host =>
      val client = system.actorOf(Props(new ClientConnection(host, clientUUID)))

      val incomingMessages: Sink[Message, NotUsed] =
        Flow[Message].to(Sink.actorRef[Message](client, PoisonPill))

      val outgoingMessages: Source[Message, NotUsed] =
        Source.actorRef[Message](10, OverflowStrategy.fail)
          .mapMaterializedValue { outActor =>
            client ! ClientConnection.Init(outActor)
            NotUsed
          }

      val flow = Flow.fromSinkAndSource(incomingMessages, outgoingMessages)

      keepAlive
        .map(message => flow.keepAlive(30 seconds, () => TextMessage(message)))
        .orElse(Some(flow))
    }

    Await.result(flow.fallbackTo(Future { None }), 1 second)
  }

  val requestHandler: HttpRequest => HttpResponse = {
    case req @ HttpRequest(GET, Uri.Path("/session"), _, _, _) =>
      val query = Query(req.uri.rawQueryString)

      req.header[UpgradeToWebSocket] match {
        case Some(upgrade) => upgrade.handleMessages(hostFlow(query.get("id"), query.get("keepAlive")))
        case None          => HttpResponse(400, entity = "Not a valid websocket request!")
      }
    case req @ HttpRequest(GET, uri, _, _, _) if uri.path.toString().startsWith("/session/") =>
      val sessionId = uri.path.toString.substring("/session/".size)
      val query = Query(req.uri.rawQueryString)

      req.header[UpgradeToWebSocket] match {
        case Some(upgrade) =>
          clientFlow(sessionId, query.get("keepAlive"), query.get("dummyFallback").contains("true"), query.get("client"))
            .map(flow => upgrade.handleMessages(flow))
            .getOrElse(HttpResponse(404, entity = "Session not found"))
        case None => HttpResponse(400, entity = "Not a valid websocket request!")
      }
    case req @ HttpRequest(GET, Uri.Path("/"), _, _, _) =>
      val filePath = "src/main/resources/index.html"

      val html =
        if (new File(filePath).exists())
          scala.io.Source.fromFile("src/main/resources/index.html").mkString
        else
          scala.io.Source.fromInputStream(getClass.getClassLoader.getResourceAsStream("index.html")).mkString

      val updatedHtml = html
        .replaceAll("\\$port", req.uri.effectivePort.toString)
        .replaceAll("\\$host", req.uri.authority.host.toString)

      HttpResponse(
        200,
        entity = HttpEntity(`text/html(UTF-8)`, updatedHtml)
      )

    case r: HttpRequest =>
      r.discardEntityBytes()
      HttpResponse(404, entity = "not found")
  }

  val port = sys.env.getOrElse("PORT", "8080").toInt

  val serverBinding = Http().bindAndHandleSync(requestHandler, interface = "0.0.0.0", port = port)

  def shutdown(): Unit = {
    println("\nShutting down relay ...")

    Thread.sleep(500)

    import system.dispatcher // for the future transformations
    serverBinding
      .flatMap(_.unbind())
      .onComplete { _ =>
        system.terminate()
      }
  }

  println(s"WebSocket Relay online at http://0.0.0.0:$port/")

  sys.addShutdownHook(shutdown)
}
