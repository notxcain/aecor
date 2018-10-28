package aecor.data

import aecor.{ Has, data }
import cats.Monad
import cats.data.EitherT
import cats.tagless.FunctorK

final case class EventsourcedBehavior[M[_[_]], F[_], S, E](actions: M[ActionT[F, S, E, ?]],
                                                           initial: S,
                                                           update: (S, E) => Folded[S]) {
  def enrich[Env](f: F[Env])(implicit M: FunctorK[M],
                             F: Monad[F]): EventsourcedBehavior[M, F, S, Enriched[Env, E]] =
    EventsourcedBehavior(
      actions =
        M.mapK(actions)(ActionT.sample[F, S, E, Env, Enriched[Env, E]](f)(Enriched(_, _))(_.event)),
      initial = initial,
      update = (s, e) => update(s, e.event)
    )
}

object EventsourcedBehavior {
  def singularRejectable[M[_[_]], F[_], State, Event, Rejection](
    actions: M[EitherT[ActionT[F, Option[State], Event, ?], Rejection, ?]],
    init: Event => Folded[State],
    applyEvent: (State, Event) => Folded[State]
  ): EventsourcedBehavior[EitherK[M, ?[_], Rejection], F, Option[State], Event] =
    EventsourcedBehavior
      .singular[EitherK[M, ?[_], Rejection], F, State, Event](EitherK(actions), init, applyEvent)

  def singular[M[_[_]], F[_], State, Event](
    actions: M[ActionT[F, Option[State], Event, ?]],
    init: Event => Folded[State],
    applyEvent: (State, Event) => Folded[State]
  ): EventsourcedBehavior[M, F, Option[State], Event] =
    data.EventsourcedBehavior(
      actions,
      Option.empty[State],
      (os, e) => os.map(s => applyEvent(s, e)).getOrElse(init(e)).map(Some(_))
    )
}

final case class Enriched[M, E](metadata: M, event: E)
object Enriched {
  implicit def hasMetadata[M, E, X](implicit M: Has[M, X]): Has[Enriched[M, E], X] =
    M.contramap(_.metadata)
  implicit def hasEvent[M, E, X](implicit E: Has[E, X]): Has[Enriched[M, E], X] =
    E.contramap(_.event)
}
