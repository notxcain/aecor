package aecor.data

import aecor.Has
import cats.Monad
import cats.data.EitherT
import cats.tagless.FunctorK
import cats.tagless.syntax.functorK._

final case class EventsourcedBehavior[M[_[_]], F[_], S, E](actions: M[ActionT[F, S, E, ?]],
                                                           create: S,
                                                           update: (S, E) => Folded[S]) {
  def enrich[Env](f: F[Env])(implicit M: FunctorK[M],
                             F: Monad[F]): EventsourcedBehavior[M, F, S, Enriched[Env, E]] =
    EventsourcedBehavior(
      actions.mapK(ActionT.sample[F, S, E, Env, Enriched[Env, E]](f)(Enriched(_, _))(_.event)),
      create,
      (s, e) => update(s, e.event)
    )
}

object EventsourcedBehavior {
  def optionalRejectable[M[_[_]], F[_], State, Event, Rejection](
    actions: M[EitherT[ActionT[F, Option[State], Event, ?], Rejection, ?]],
    create: Event => Folded[State],
    update: (State, Event) => Folded[State]
  ): EventsourcedBehavior[EitherK[M, Rejection, ?[_]], F, Option[State], Event] =
    EventsourcedBehavior
      .optional[EitherK[M, Rejection, ?[_]], F, State, Event](EitherK(actions), create, update)

  def optional[M[_[_]], F[_], State, Event](
    actions: M[ActionT[F, Option[State], Event, ?]],
    create: Event => Folded[State],
    update: (State, Event) => Folded[State]
  ): EventsourcedBehavior[M, F, Option[State], Event] =
    EventsourcedBehavior(
      actions,
      Option.empty[State],
      (os, e) => os.map(s => update(s, e)).getOrElse(create(e)).map(Some(_))
    )
}

final case class Enriched[M, E](metadata: M, event: E)
object Enriched {
  implicit def hasMetadata[M, E, X](implicit M: Has[M, X]): Has[Enriched[M, E], X] =
    M.contramap(_.metadata)
  implicit def hasEvent[M, E, X](implicit E: Has[E, X]): Has[Enriched[M, E], X] =
    E.contramap(_.event)
}
