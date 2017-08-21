package uk.co.goldsaucer

import akka.actor.{Actor, ActorContext, ActorRef, ActorRefFactory, ActorSystem, Props, Terminated}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class StatsTracker extends Actor with Logs {
  import StatsTracker._

  var numHostsOnline: Int = 0
  var numHostsLookingToJoinSession: Int = 0

  override def receive: Receive = {
    case RequestStats =>
      log.debug(s"${sender} requesting stats")
      sender ! Stats(numHostsOnline, numHostsLookingToJoinSession)
    case HostConnected =>
      numHostsOnline = numHostsOnline + 1
      context.watch(sender)
      log.debug(s"host connected ($numHostsOnline)")
    case Terminated(host) =>
      numHostsOnline = math.max(numHostsOnline - 1, 0)
      log.debug(s"host disconnected ($numHostsOnline)")
    case HostLookingForSession =>
      numHostsLookingToJoinSession = numHostsLookingToJoinSession + 1
      log.debug(s"host looking for session ($numHostsLookingToJoinSession)")
    case HostStoppedLookingForSession =>
      numHostsLookingToJoinSession = math.max(numHostsLookingToJoinSession - 1, 0)
      log.debug(s"host stopped looking for session ($numHostsLookingToJoinSession)")
  }
}

object StatsTracker extends Logs {
  val actorName = "stats-tracker"
  val actorPath = "user/stats-tracker"

  def init()(implicit system: ActorSystem): Unit = {
    system.actorOf(Props(classOf[StatsTracker]), "stats-tracker") // start stats tracker
  }

  def actor(implicit context: ActorRefFactory): ActorRef = {
    val fut = context.actorSelection(actorPath).resolveOne(1.second)

    Await.result(fut, 1.second)
  }

  def actorDo(block: ActorRef => Unit)(implicit context: ActorRefFactory): Unit = {
    val system: ActorRefFactory = context match {
      case system: ActorSystem => system
      case context: ActorContext => context.system
      case other => other
    }

    val sel = system.actorSelection(actorPath).resolveOne(1.second)

    sel.onComplete { result =>
      if (result.isFailure) {
        log.error("Failed to address StatsTracker: " + result)
      }
    }

    sel.foreach(block)
  }

  trait Event {
    def track(implicit context: ActorRefFactory, sender: ActorRef = Actor.noSender): Unit =
      StatsTracker.actorDo(_ ! this)
  }

  def !(message: Any)(implicit context: ActorRefFactory): Unit = actorDo(_ ! message)

  case object RequestStats extends Event
  case class Stats(
    numHostsOnline: Int,
    numHostsLookingToJoinSession: Int
  ) extends Event

  case object HostConnected extends Event
  case object HostLookingForSession extends Event
  case object HostStoppedLookingForSession extends Event
}
