package aecor.data

import cats.~>

import scala.collection.immutable.Seq

final case class EventsourcedBehavior[F[_], Op[_], State, Event](
  handler: Op ~> Handler[F, State, Seq[Event], ?],
  folder: Folder[Folded, Event, State]
)

object EventsourcedBehavior {
  type Handler[F[_], S, E, A] = S => F[(Seq[E], A)]
}
