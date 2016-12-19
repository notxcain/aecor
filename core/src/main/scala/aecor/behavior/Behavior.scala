package aecor.behavior

import cats.~>

final case class Behavior[Command[_], State, Event](
  commandHandler: Command ~> Handler[State, Event, ?],
  initialState: State,
  projector: (State, Event) => State
)
