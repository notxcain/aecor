package aecor.core.aggregate

import aecor.core.actor.EventsourcedBehavior
import akka.Done
import cats.data.Xor

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext

trait AggregateBehavior[A] {
  type Command[_]
  type State
  type Event

  def handleCommand[Response](a: A)(state: State, command: Command[Response]): (Response, Seq[Event])

  def init: State

  def applyEvent(state: State, event: Event): State
}

object AggregateBehavior {
  object syntax {
    def accept[R, E](events: E*): (Xor[R, Done], Seq[E]) = (Xor.right(Done), events.toVector)

    def reject[R, E](rejection: R): (Xor[R, Done], Seq[E]) = (Xor.left(rejection), Seq.empty)

    implicit class AnyOps[A](a: A) {
      def withEvents[E](events: E*): (A, Seq[E]) = (a, Seq(events: _*))
      def noEvents[E]: (A, Seq[E]) = (a, Seq.empty)
    }
  }

  type Aux[A, Command0[_], State0, Event0] = AggregateBehavior[A] {
    type Command[X] = Command0[X]
    type State = State0
    type Event = Event0
  }
}

object AggregateEventsourcedBehavior {

  implicit def instance[A, ACommand[_], AState, AEvent]
  (implicit A: AggregateBehavior.Aux[A, ACommand, AState, AEvent], ec: ExecutionContext): EventsourcedBehavior.Aux[A, ACommand, AState, AEvent] =
    new EventsourcedBehavior[A] {
      override type Command[X] = ACommand[X]
      override type State = AState
      override type Event = AEvent

      override def handleCommand[R](a: A)(state: State, command: Command[R]): (R, Seq[Event]) = {
        A.handleCommand(a)(state, command)
      }

      override def init(a: A): AState =
        A.init

      override def applyEvent(a: A)(state: AState, event: AEvent): AState =
        A.applyEvent(state, event)
    }

}

