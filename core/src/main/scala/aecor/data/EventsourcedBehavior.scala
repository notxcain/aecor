package aecor.data

import cats.~>

final case class EventsourcedBehavior[F[_], Op[_], State, Event](
  handler: Op ~> Handler[F, State, Event, ?],
  folder: Folder[Folded, Event, State]
)
