package aecor.data

import aecor.Has
import aecor.arrow.Invocation
import cats.{ Monad, ~> }
import cats.data.{ Chain, EitherT }
import cats.tagless.FunctorK
import cats.tagless.syntax.functorK._

final case class EventsourcedBehavior[M[_[_]], F[_], S, E](actions: M[ActionT[F, S, E, ?]],
                                                           fold: Fold[Folded, S, E]) {
  def enrich[Env](f: F[Env])(implicit M: FunctorK[M],
                             F: Monad[F]): EventsourcedBehavior[M, F, S, Enriched[Env, E]] =
    EventsourcedBehavior(
      actions.mapK(ActionT.sampleK[F, S, E, Env, Enriched[Env, E]](f)(Enriched(_, _))(_.event)),
      fold.contramap(_.event)
    )

  def mapK[G[_]](fg: F ~> G)(implicit M: FunctorK[M]): EventsourcedBehavior[M, G, S, E] =
    copy(actions.mapK(λ[ActionT[F, S, E, ?] ~> ActionT[G, S, E, ?]](_.mapK(fg))))

  def run[A](state: S, invocation: Invocation[M, A]): F[Folded[(Chain[E], A)]] =
    invocation
      .run(actions)
      .run(fold.init(state))
}

object EventsourcedBehavior extends EventsourcedBehaviourIntances {
  def rejectable[M[_[_]], F[_], S, E, R](
    actions: M[EitherT[ActionT[F, S, E, ?], R, ?]],
    fold: Fold[Folded, S, E]
  ): EventsourcedBehavior[EitherK[M, R, ?[_]], F, S, E] =
    EventsourcedBehavior[EitherK[M, R, ?[_]], F, S, E](EitherK(actions), fold)
}

trait EventsourcedBehaviourIntances {
  implicit def eventsourcedBehaviourFunctorKInstance[M[_[_]]: FunctorK, S, E]
    : FunctorK[EventsourcedBehavior[M, ?[_], S, E]] =
    new FunctorK[EventsourcedBehavior[M, ?[_], S, E]] {
      def mapK[F[_], G[_]](a: EventsourcedBehavior[M, F, S, E])(
        f: F ~> G
      ): EventsourcedBehavior[M, G, S, E] = a.mapK(f)
    }
}

final case class Enriched[M, E](metadata: M, event: E)
object Enriched {
  implicit def hasMetadata[M, E, X](implicit M: Has[M, X]): Has[Enriched[M, E], X] =
    M.contramap(_.metadata)
  implicit def hasEvent[M, E, X](implicit E: Has[E, X]): Has[Enriched[M, E], X] =
    E.contramap(_.event)
}
