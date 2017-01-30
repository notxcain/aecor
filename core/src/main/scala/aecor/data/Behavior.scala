package aecor.data

import cats.~>

final case class Behavior[Command[_], State, Event](
  commandHandler: Command ~> Handler[State, Event, ?],
  init: State,
  update: (State, Event) => Folded[State]
)
