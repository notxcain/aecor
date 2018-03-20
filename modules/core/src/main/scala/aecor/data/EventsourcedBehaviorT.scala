package aecor.data

import cats.{ Applicative, Functor }
import io.aecor.liberator.FunctorK

final case class EventsourcedBehaviorT[M[_[_]], F[_], State, Event](
  actions: M[ActionT[F, State, Event, ?]],
  initialState: State,
  applyEvent: (State, Event) => Folded[State]
)

final case class EventsourcedBehavior[M[_[_]], S, E](actions: M[Action[S, E, ?]],
                                                     initialState: S,
                                                     applyEvent: (S, E) => Folded[S]) {
  def enrich[F[_]: Functor, Env](
    fm: F[Env]
  )(implicit M: FunctorK[M]): EventsourcedBehaviorT[M, F, S, Enriched[Env, E]] =
    EventsourcedBehaviorT(
      M.mapK(actions, ActionT.enrich(fm)),
      initialState,
      (s, e) => applyEvent(s, e.event)
    )

  def lifted[F[_]: Applicative](implicit M: FunctorK[M]): EventsourcedBehaviorT[M, F, S, E] =
    EventsourcedBehaviorT(M.mapK(actions, ActionT.liftK[F, S, E]), initialState, applyEvent)
}

final case class Enriched[M, E](metadata: M, event: E)

object EventsourcedBehavior {
  def optional[M[_[_]], State, Event](
    actions: M[Action[Option[State], Event, ?]],
    init: Event => Folded[State],
    applyEvent: (State, Event) => Folded[State]
  ): EventsourcedBehavior[M, Option[State], Event] =
    EventsourcedBehavior(
      actions,
      Option.empty[State],
      (os, e) => os.map(s => applyEvent(s, e)).getOrElse(init(e)).map(Some(_))
    )
}
