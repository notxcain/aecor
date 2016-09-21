package aecor.core.aggregate

import akka.Done
import cats.data.Xor

import scala.collection.immutable.Seq

trait AggregateBehavior[A] {
  type Command[_]
  type State
  type Event

  def handleCommand[Response](a: A)(state: State, command: Command[Response]): AggregateBehavior.CommandHandlerResult[Response, Event]

  def init: State

  def applyEvent(state: State, event: Event): State
}

object AggregateBehavior {
  object syntax {
    def accept[R, E](events: E*): (Xor[R, Done], Seq[E]) = (Xor.right(Done), events.toVector)

    def reject[R, E](rejection: R): (Xor[R, Done], Seq[E]) = (Xor.left(rejection), Seq.empty)
  }

  type Aux[A, Command0[_], State0, Event0] = AggregateBehavior[A] {
    type Command[X] = Command0[X]
    type State = State0
    type Event = Event0
  }

  type CommandHandlerResult[Response, Event] = (Response, Seq[Event])
}

