package aecor.core.aggregate

import cats.~>
import scala.collection.immutable.Seq

object behavior {

  type Handler[State, Event, A] = State => (Seq[Event], A)
  def Handler[State, Event, A](
      f: State => (Seq[Event], A)): Handler[State, Event, A] = f

  final case class Behavior[Command[_], State, Event](
      commandHandler: Command ~> Handler[State, Event, ?],
      initialState: State,
      projector: (State, Event) => State
  )
}

trait Folder[S, E] {
  def zero: S
  def update(s: S, e: E): S
}
