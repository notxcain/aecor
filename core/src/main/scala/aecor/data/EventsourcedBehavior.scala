package aecor.data

import cats.~>

final case class EventsourcedBehavior[F[_], Op[_], State, Event](
  initialState: State,
  commandHandler: Op ~> Action[F, State, Event, ?],
  applyEvent: (State, Event) => Folded[State]
)

object EventsourcedBehavior {
  def optional[F[_], Op[_], State, Event](
    init: Event => Folded[State],
    commandHandler: Op ~> Action[F, Option[State], Event, ?],
    applyEvent: (State, Event) => Folded[State]
  ): EventsourcedBehavior[F, Op, Option[State], Event] =
    EventsourcedBehavior(
      Option.empty[State],
      commandHandler,
      (os, e) => os.map(s => applyEvent(s, e).map(Some(_))).getOrElse(Folded.impossible)
    )

}
