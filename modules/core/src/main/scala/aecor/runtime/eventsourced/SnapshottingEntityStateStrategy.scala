package aecor.runtime.eventsourced
import aecor.data.ActionT
import aecor.runtime.Eventsourced.{ Snapshotting, Versioned }
import cats.Monad
import cats.effect.Sync
import cats.implicits._

final class SnapshottingStateStrategyBuilder[F[_]: Monad, K, S, E](
  snapshotting: Snapshotting[F, K, S],
  innerStateStrategy: StateStrategyBuilder[K, F, S, E]
) extends StateStrategyBuilder[K, F, S, E] {

  override def x(key: K): F[Stage.X[F, S, E]] = innerStateStrategy.create(key).map { ss =>
    new Stage.X[F, S, E] {
      override def recoverAndRun[A](current: Versioned[S], action: ActionT[F, S, E, A]): F[A] =
        for {
          recovered <- snapshotting.load(key).map(_.getOrElse(current)).flatMap(ss.recoverState)
          (next, a) <- ss.run(recovered, action)
          _ <- snapshotting.snapshotIfNeeded(key, current.version, next)
        } yield a

    }
  }

  override def create(key: K): F[EntityStateStrategy[F, S, E]] =
    innerStateStrategy.create(key).map { inner =>
      new EntityStateStrategy[F, S, E] {

        override def recoverState(initial: Versioned[S]): F[Versioned[S]] =
          for {
            effectiveInitial <- snapshotting.load(key).map(_.getOrElse(initial))
            out <- inner.recoverState(effectiveInitial)
          } yield out

        override def run[A](current: Versioned[S],
                            action: ActionT[F, S, E, A]): F[(Versioned[S], A)] =
          for {
            (next, a) <- inner.run(current, action)
            _ <- snapshotting.snapshotIfNeeded(key, current.version, next)
          } yield (next, a)

      }
    }

  def withInmemCache(implicit F: Sync[F]): InMemCachingStateStrategyBuilder[K, F, S, E] =
    InMemCachingStateStrategyBuilder(this)
}
