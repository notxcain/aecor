package aecor.runtime.eventsourced

import aecor.data.ActionT
import aecor.runtime.Eventsourced.{ Snapshotting, Versioned }
import cats.arrow.FunctionK
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import cats.{ Monad, ~> }

object ActionRunner {

  def apply[F[_]: Monad, K, S, E](key: K,
                                  stateStrategy: EventsourcedState[F, K, S, E],
                                  snapshotting: Snapshotting[F, K, S]): ActionT[F, S, E, ?] ~> F =
    apply(key, stateStrategy, snapshotting, FunctionK.id)

  def apply[F[_]: Monad, G[_]: Monad, K, S, E](key: K,
                                               stateStrategy: EventsourcedState[G, K, S, E],
                                               snapshotting: Snapshotting[F, K, S],
                                               boundary: G ~> F): ActionT[G, S, E, ?] ~> F =
    Lambda[ActionT[G, S, E, ?] ~> F] { action =>
      for {
        snapshot <- snapshotting.load(key)
        (before, (after, a)) <- boundary {
                                 stateStrategy
                                   .recover(key, snapshot)
                                   .flatMap(
                                     state =>
                                       stateStrategy
                                         .run(key, state, action)
                                         .map(out => (state, out))
                                   )
                               }
        _ <- snapshotting.snapshotIfNeeded(key, before, after)
      } yield a
    }

  def cached[F[_]: Sync, K, S, E](
    key: K,
    stateStrategy: EventsourcedState[F, K, S, E],
    snapshotting: Snapshotting[F, K, S]
  ): F[ActionT[F, S, E, ?] ~> F] =
    Ref[F].of(none[Versioned[S]]).map { cache =>
      Lambda[ActionT[F, S, E, ?] ~> F] { action =>
        for {
          (before, (after, a)) <- cache.get.flatMap {
                                   case Some(state) =>
                                     stateStrategy.run(key, state, action).map((state, _))
                                   case None =>
                                     snapshotting
                                       .load(key)
                                       .flatMap { snapshot =>
                                         stateStrategy.recover(key, snapshot).flatMap { state =>
                                           stateStrategy
                                             .run(key, state, action)
                                             .map((state, _))
                                         }
                                       }
                                 }
          _ <- snapshotting.snapshotIfNeeded(key, before, after)
          _ <- cache.set(after.some)
        } yield a
      }
    }

}
