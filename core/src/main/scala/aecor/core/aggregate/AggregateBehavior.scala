package aecor.core.aggregate

import akka.Done

import scala.collection.immutable.Seq

trait AggregateBehavior[A] {
  type Command[_]
  type State
  type Event

  def handleCommand[Response](a: A)(state: State, command: Command[Response])
    : AggregateBehavior.CommandHandlerResult[Response, Event]

  def init: State

  def applyEvent(state: State, event: Event): State
}

object AggregateBehavior {
  object syntax {
    def accept[R, E](events: E*): (Seq[E], Either[R, Done]) =
      (events.toVector, Right(Done))

    def reject[R, E](rejection: R): (Seq[E], Either[R, Done]) =
      (Seq.empty, Left(rejection))
  }

  type Aux[A, Command0[_], State0, Event0] = AggregateBehavior[A] {
    type Command[X] = Command0[X]
    type State = State0
    type Event = Event0
  }

  type CommandHandlerResult[Response, Event] = (Response, Seq[Event])
}
