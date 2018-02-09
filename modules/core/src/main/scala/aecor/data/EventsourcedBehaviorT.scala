package aecor.data

import cats.{ Applicative, Functor, ~> }
import io.aecor.liberator.Algebra

final case class EventsourcedBehaviorT[F[_], Op[_], State, Event](
  initialState: State,
  commandHandler: Op ~> ActionT[F, State, Event, ?],
  applyEvent: (State, Event) => Folded[State]
)

final case class EventsourcedBehavior[Op[_], S, E](commandHandler: Op ~> Action[S, E, ?],
                                                   initialState: S,
                                                   applyEvent: (S, E) => Folded[S]) {
  def enrich[F[_]: Functor, M](fm: F[M]): EventsourcedBehaviorT[F, Op, S, Enriched[M, E]] =
    EventsourcedBehaviorT(
      initialState,
      commandHandler.andThen(ActionT.enrich(fm)),
      (s, e) => applyEvent(s, e.event)
    )

  def plain[F[_]: Applicative]: EventsourcedBehaviorT[F, Op, S, E] =
    EventsourcedBehaviorT(initialState, commandHandler.andThen(ActionT.liftK[F, S, E]), applyEvent)
}

final case class Enriched[M, E](metadata: M, event: E)

object EventsourcedBehavior {

  def optionalM[M[_[_]], State, Event](
    commandHandler: M[Action[Option[State], Event, ?]],
    init: Event => Folded[State],
    applyEvent: (State, Event) => Folded[State]
  )(implicit M: Algebra[M]): EventsourcedBehavior[M.Out, Option[State], Event] =
    optional(M.toFunctionK(commandHandler), init, applyEvent)

  def optional[Op[_], State, Event](
    commandHandler: Op ~> Action[Option[State], Event, ?],
    init: Event => Folded[State],
    applyEvent: (State, Event) => Folded[State]
  ): EventsourcedBehavior[Op, Option[State], Event] =
    EventsourcedBehavior(
      commandHandler,
      Option.empty[State],
      (os, e) => os.map(s => applyEvent(s, e).map(Some(_))).getOrElse(Folded.impossible)
    )
}
