package aecor.core.aggregate

import java.time.Instant

import aecor.core.actor.NowOrDeferred.{Deferred, Now}
import aecor.core.actor.{EventsourcedBehavior, EventsourcedState, NowOrDeferred}
import aecor.util._

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}

case class CommandId(value: String) extends AnyVal

case class EventId(value: String) extends AnyVal

case class AggregateEvent[+Event](id: EventId, event: Event, timestamp: Instant)

sealed trait AggregateResponse[+Rejection]

object AggregateResponse {
  def accepted[R]: AggregateResponse[R] = Accepted

  def rejected[R](rejection: R): AggregateResponse[R] = Rejected(rejection)

  case object Accepted extends AggregateResponse[Nothing]

  case class Rejected[+Rejection](rejection: Rejection) extends AggregateResponse[Rejection]

}

trait AggregateBehavior[A, Command[_]] {
  type State
  type Event

  def handleCommand[Response](a: A)(state: State, command: Command[Response]): NowOrDeferred[(Response, Seq[Event])]

  def init(a: A): State

  def applyEvent(state: State, event: Event): State
}

object AggregateBehavior {
  type AggregateDecision[Rejection, Event] = (AggregateResponse[Rejection], Seq[Event])

  object syntax {
    def accept[R, E](events: E*): Now[AggregateDecision[R, E]] = Now(AggregateResponse.accepted -> events.toVector)

    def reject[R, E](rejection: R): Now[AggregateDecision[R, E]] = Now(AggregateResponse.rejected(rejection) -> Vector.empty)

    def defer[R, E](f: ExecutionContext => Future[AggregateDecision[R, E]]): NowOrDeferred[AggregateDecision[R, E]] = Deferred(f)

    object now {
      def accept[R, E](events: E*): AggregateDecision[R, E] = AggregateResponse.accepted -> events.toVector

      def reject[R, E](rejection: R): AggregateDecision[R, E] = AggregateResponse.rejected(rejection) -> Vector.empty
    }

    implicit def fromNow[A](now: Now[A]): A = now.value

    def handle[A, B, Out](a: A, b: B)(f: A => B => Out): Out = f(a)(b)
  }

  type Aux[A, Command[_], State0, Event0] = AggregateBehavior[A, Command] {
    type State = State0
    type Event = Event0
  }
}

case class AggregateEventsourcedBehavior[Aggregate](aggregate: Aggregate)

object AggregateEventsourcedBehavior {

  case class State[Inner](underlying: Inner)

  implicit def instance[A, Command[_]]
  (implicit A: AggregateBehavior[A, Command], ec: ExecutionContext): EventsourcedBehavior.Aux[AggregateEventsourcedBehavior[A], Command, AggregateEventsourcedBehavior.State[A.State], AggregateEvent[A.Event]] =
    new EventsourcedBehavior[AggregateEventsourcedBehavior[A], Command] {
      override type Event = AggregateEvent[A.Event]
      override type State = AggregateEventsourcedBehavior.State[A.State]

      override def handleCommand[R](a: AggregateEventsourcedBehavior[A])(state: State, command: Command[R]): Future[(R, Seq[AggregateEvent[A.Event]])] =
        A.handleCommand(a.aggregate)(state.underlying, command).map {
          case (x, events) => x -> events.map(event => AggregateEvent(generate[EventId], event, Instant.now()))
        }.asFuture

      override def init(a: AggregateEventsourcedBehavior[A]): State =
        AggregateEventsourcedBehavior.State(A.init(a.aggregate))
    }

  implicit def projection[A, Command[_]](implicit A: AggregateBehavior[A, Command]): EventsourcedState[AggregateEventsourcedBehavior.State[A.State], AggregateEvent[A.Event]] =
    new EventsourcedState[AggregateEventsourcedBehavior.State[A.State], AggregateEvent[A.Event]] {
      override def applyEvent(a: State[A.State], e: AggregateEvent[A.Event]): State[A.State] =
        a.copy(underlying = A.applyEvent(a.underlying, e.event))
    }
}

