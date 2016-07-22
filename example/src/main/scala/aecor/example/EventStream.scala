package aecor.example

import java.util.UUID
import java.util.concurrent.TimeoutException

import aecor.example.EventStream.ObserverControl
import aecor.example.EventStreamObserverRegistry._
import akka.Done
import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout

import scala.concurrent.{Future, Promise}

object EventStream {
  case class ObserverControl[A](id: ObserverId, result: Future[A])
  type ObserverId = String
}

trait EventStream[Event] {
  def registerObserver[A](f: PartialFunction[Event, A])(implicit timeout: Timeout): Future[ObserverControl[A]]
}

class DefaultEventStream[Event](actorSystem: ActorSystem, source: Source[Event, Any])(implicit materializer: Materializer) extends EventStream[Event] {
  import akka.pattern.ask
  val actor = actorSystem.actorOf(Props(new EventStreamObserverRegistry[Event]), "event-stream-observer-registry")
  source.map(HandleEvent(_)).runWith(Sink.actorRefWithAck(actor, Init, Done, ShutDown))
  override def registerObserver[A](f: PartialFunction[Event, A])(implicit timeout: Timeout): Future[ObserverControl[A]] = {
    import materializer.executionContext
    (actor ? RegisterObserver(f, timeout)).mapTo[ObserverRegistered[A]].map(_.control)
  }
}

object EventStreamObserverRegistry {
  sealed trait Command[+Event]
  case object Init extends Command[Nothing]
  case class RegisterObserver[Event, A](f: PartialFunction[Event, A], timeout: Timeout) extends Command[Event]
  case class DeregisterObserver(id: String) extends Command[Nothing]
  case class HandleEvent[Event](event: Event) extends Command[Event]
  case object ShutDown extends Command[Nothing]

  case class ObserverRegistered[A](control: ObserverControl[A])
}

class EventStreamObserverRegistry[Event] extends Actor with ActorLogging {
  import EventStreamObserverRegistry._

  def scheduler = context.system.scheduler
  implicit def executionContext = context.dispatcher

  case class Observer(f: PartialFunction[Event, Any], promise: Promise[Any]) {
    def handleEvent(event: Event): Boolean = {
      val handled = f.isDefinedAt(event)
      if (handled) {
        promise.success(f(event))
      }
      handled
    }
  }

  var observers = Map.empty[String, Observer]

  override def receive: Receive = {
    case command: Command[Event] => handleCommand(command)
  }

  def handleCommand(command: Command[Event]): Unit = command match {
    case Init =>
      sender() ! Done
    case RegisterObserver(f, timeout) =>
      val id = UUID.randomUUID().toString
      val promise = Promise[Any]
      observers = observers.updated(id,  Observer(f.asInstanceOf[PartialFunction[Event, Any]], promise))
      scheduler.scheduleOnce(timeout.duration) {
        if (!promise.isCompleted) {
          promise.failure(new TimeoutException())
          self ! DeregisterObserver(id)
        }
      }
      sender() ! ObserverRegistered(ObserverControl(id, promise.future))
    case HandleEvent(event) =>
      observers = observers.filterNot {
        case (id, observer) => observer.handleEvent(event)
      }
      sender() ! Done
    case DeregisterObserver(id) =>
      observers = observers - id
    case ShutDown =>
      observers.values.foreach(_.promise.failure(new TimeoutException()))
      sender() ! Done
  }
}
