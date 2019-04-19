package aecor.runtime.eventsourced
import aecor.data.ActionT
import aecor.runtime.Eventsourced.Versioned
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._

final class InMemCachingStateStrategyBuilder[K, F[_]: Sync, S, E] private (
  innerStateStrategy: StateStrategyBuilder[K, F, S, E]
) extends StateStrategyBuilder[K, F, S, E] {

  override def x(key: K): F[Stage.X[F, S, E]] = Ref[F].of(none[Versioned[S]]).flatMap { cache =>
    innerStateStrategy.create(key).map { ss =>
      new Stage.X[F, S, E] {
        override def recoverAndRun[A](current: Versioned[S], action: ActionT[F, S, E, A]): F[A] =
          for {
            recovered <- cache.get.flatMap {
                          case Some(s) => s.pure[F]
                          case None    => ss.recoverState(current)
                        }
            (next, a) <- ss.run(recovered, action)
            _ <- cache.set(next.some)
          } yield a
      }
    }
  }

  override def create(key: K): F[EntityStateStrategy[F, S, E]] =
    Ref[F].of(none[Versioned[S]]).flatMap { cache =>
      innerStateStrategy.create(key).map { inner =>
        new EntityStateStrategy[F, S, E] {
          override def recoverState(initial: Versioned[S]): F[Versioned[S]] =
            cache.get.flatMap {
              case Some(s) => s.pure[F]
              case None    => inner.recoverState(initial)
            }

          override def run[A](current: Versioned[S],
                              action: ActionT[F, S, E, A]): F[(Versioned[S], A)] =
            for {
              (next, a) <- inner.run(current, action)
              _ <- cache.set(next.some)
            } yield (next, a)

        }
      }
    }
}

object InMemCachingStateStrategyBuilder {
  def apply[K, F[_]: Sync, S, E](
    inner: StateStrategyBuilder[K, F, S, E]
  ): InMemCachingStateStrategyBuilder[K, F, S, E] =
    new InMemCachingStateStrategyBuilder(inner)
}
