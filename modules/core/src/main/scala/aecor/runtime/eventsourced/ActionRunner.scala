package aecor.runtime.eventsourced

import aecor.data.ActionT
import aecor.runtime.Eventsourced.{ Snapshotting, Versioned }
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import cats.{ Monad, ~> }

object ActionRunner {

  def apply[F[_]: Monad, G[_]: Monad, K, S, E](key: K,
                                               stateStrategy: EventsourcedState[G, K, S, E],
                                               snapshotting: Snapshotting[F, K, S],
                                               journalBoundary: G ~> F): ActionT[G, S, E, ?] ~> F =
    Lambda[ActionT[G, S, E, ?] ~> F] { action =>
      for {
        snapshot <- snapshotting.load(key)
        (before, (after, a)) <- journalBoundary {
                                 stateStrategy
                                   .recover(key, snapshot)
                                   .flatMap { state =>
                                     stateStrategy
                                       .run(key, state, action)
                                       .map(out => (state, out))
                                   }
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
          before <- cache.get.flatMap {
                     case Some(state) =>
                       state.pure[F]
                     case None =>
                       snapshotting
                         .load(key)
                         .flatMap(stateStrategy.recover(key, _))
                   }
          (after, a) <- stateStrategy
                         .run(key, before, action)
          _ <- snapshotting.snapshotIfNeeded(key, before, after)
          _ <- cache.set(after.some)
        } yield a
      }
    }

}
