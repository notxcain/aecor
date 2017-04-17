package aecor.example

import java.util.UUID

import aecor.example.EventStream.ObserverControl
import aecor.example.DefaultEventStream.Observer
import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }
import monix.eval.{ MVar, Task }
import monix.execution.Scheduler

import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration

object EventStream {

  case class ObserverControl[F[_], A](id: ObserverId, result: F[A])

  type ObserverId = String
}

trait EventStream[F[_], Event] {
  def registerObserver[A](timeout: FiniteDuration)(
    f: PartialFunction[Event, A]
  ): F[ObserverControl[F, A]]
}

class DefaultEventStream[E](state: MVar[Map[String, Observer[E, _]]])
    extends EventStream[Task, E] {

  override def registerObserver[A](
    timeout: FiniteDuration
  )(f: PartialFunction[E, A]): Task[ObserverControl[Task, A]] =
    for {
      observers <- state.take
      id = UUID.randomUUID().toString
      promise = Promise[A]
      next = observers.updated(id, Observer(f, promise))
      _ <- state.put(next)
    } yield ObserverControl(id, Task.fromFuture(promise.future))
}

object DefaultEventStream {

  case class Observer[E, A](f: PartialFunction[E, A], promise: Promise[A]) {
    def handleEvent(event: E): Boolean =
      f.lift(event) match {
        case Some(x) =>
          promise.success(x)
          true
        case None =>
          false
      }
  }
  def run[E](source: Source[E, NotUsed])(implicit materializer: Materializer) = {
    val state = MVar(Map.empty[String, Observer[E, _]])
    for {
      _ <- Task.defer {
            Task.fromFuture {
              source
                .mapAsync(1) { x =>
                  state.take
                    .flatMap { c =>
                      val next = c.filterNot {
                        case (_, observer) => observer.handleEvent(x)
                      }
                      state.put(next)
                    }
                    .runAsync(Scheduler(materializer.executionContext))
                }
                .runWith(Sink.ignore)
            }
          }
    } yield new DefaultEventStream[E](state)
  }
}
