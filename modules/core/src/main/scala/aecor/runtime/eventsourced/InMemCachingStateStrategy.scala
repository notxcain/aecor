package aecor.runtime.eventsourced
import aecor.data.ActionT
import aecor.runtime.Eventsourced.Versioned
import cats.data.NonEmptyChain
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._

final class InMemCachingStateStrategy[K, F[_]: Sync, S, E] private (
  innerStateStrategy: StateStrategy[K, F, S, E]
) extends StateStrategy[K, F, S, E] {

  override def focus(key: K): F[EntityStateStrategy[F, S, E]] =
    Ref[F].of(none[Versioned[S]]).flatMap { cache =>
      innerStateStrategy.focus(key).map { inner =>
        new EntityStateStrategy[F, S, E] {
          override def recoverState(initial: Versioned[S]): F[Versioned[S]] =
            cache.get.flatMap {
              case Some(s) => s.pure[F]
              case None    => inner.recoverState(initial)
            }

          override def runAction[A](current: Versioned[S],
                                    action: ActionT[F, S, E, A]): F[(Versioned[S], A)] =
            for {
              (next, a) <- inner.runAction(current, action)
              _ <- cache.set(next.some)
            } yield (next, a)

          override def updateState(current: Versioned[S], es: NonEmptyChain[E]): F[Versioned[S]] =
            for {
              next <- inner.updateState(current, es)
              _ <- cache.set(next.some)
            } yield next
        }
      }
    }
}

object InMemCachingStateStrategy {
  def apply[K, F[_]: Sync, S, E](
    inner: StateStrategy[K, F, S, E]
  ): InMemCachingStateStrategy[K, F, S, E] =
    new InMemCachingStateStrategy(inner)
}
