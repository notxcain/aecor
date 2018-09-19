package aecor.data

import aecor.{Has, data}
import cats.Monad
import io.aecor.liberator.FunctorK

final case class EventsourcedBehavior[M[_[_]], F[_], S, E, R](actions: M[ActionT[F, S, E, R, ?]], initial: S, update: (S, E) => Folded[S]) {
  def enrich[Env](f: F[Env])(implicit M: FunctorK[M], F: Monad[F]): EventsourcedBehavior[M, F, S, Enriched[Env, E], R] =
    EventsourcedBehavior(
      actions = M.mapK(actions, ActionT.sample[F, S, E, R, Env, Enriched[Env, E]](f)(Enriched(_, _))(_.event)),
      initial = initial,
      update = (s, e) => update(s, e.event)
    )
}

object EventsourcedBehavior {
  def optional[M[_[_]], F[_], State, Event, Rejection](
                                       actions: M[ActionT[F, Option[State], Event, Rejection, ?]],
                                       init: Event => Folded[State],
                                       applyEvent: (State, Event) => Folded[State]
                                     ): EventsourcedBehavior[M, F, Option[State], Event, Rejection] =
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
