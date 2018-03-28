package aecor.data

import aecor.IsK
import cats.{ Applicative, FlatMap, Id, Monad, ~> }
import io.aecor.liberator.FunctorK

final case class EventsourcedBehaviorT[M[_[_]], F[_], S, E](actions: M[ActionT[F, S, E, ?]],
                                                            initialState: S,
                                                            applyEvent: (S, E) => Folded[S]) {
  def enrich[Env](env: F[Env])(implicit M: FunctorK[M],
                               F: FlatMap[F]): EventsourcedBehaviorT[M, F, S, Enriched[Env, E]] =
    EventsourcedBehaviorT(
      M.mapK(actions, ActionT.mapEventsF(env)),
      initialState,
      (s, e) => applyEvent(s, e.event)
    )

  def mapEventsF[E1](f: List[E] => F[List[E1]])(implicit M: FunctorK[M],
                                                F: FlatMap[F]): EventsourcedBehaviorT[M, F, S, E1] =
    EventsourcedBehaviorT(
      M.mapK(actions, ActionT.mapEventsF(f)),
      initialState,
      (s, e) => applyEvent(s, e.event)
    )

  def liftEnrich[G[_], Env](fm: G[Env])(
    implicit M: FunctorK[M],
    G: Monad[G],
    F: F IsK Id
  ): EventsourcedBehaviorT[M, G, S, Enriched[Env, E]] =
    lift[G].enrich(fm)

  def lift[G[_]](implicit G: Applicative[G],
                 F: IsK[F, Id],
                 M: FunctorK[M]): EventsourcedBehaviorT[M, G, S, E] =
    mapK(new (F ~> G) {
      final override def apply[A](fa: F[A]): G[A] =
        G.pure(F.substitute(fa))
    })

  def mapK[G[_]](f: F ~> G)(implicit M: FunctorK[M]): EventsourcedBehaviorT[M, G, S, E] =
    copy(actions = M.mapK(actions, ActionT.mapK(f)))
}

final case class Enriched[M, E](metadata: M, event: E)
