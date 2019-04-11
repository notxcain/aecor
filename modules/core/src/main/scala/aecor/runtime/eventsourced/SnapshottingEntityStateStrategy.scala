package aecor.runtime.eventsourced
import aecor.data.ActionT
import aecor.runtime.Eventsourced.{ Snapshotting, Versioned }
import cats.Monad
import cats.data.NonEmptyChain
import cats.effect.Sync
import cats.implicits._

final class SnapshottingStateStrategy[F[_]: Monad, K, S, E](
  snapshotting: Snapshotting[F, K, S],
  innerStateStrategy: StateStrategy[K, F, S, E]
) extends StateStrategy[K, F, S, E] {

  override def focus(key: K): F[EntityStateStrategy[F, S, E]] =
    innerStateStrategy.focus(key).map { inner =>
      new EntityStateStrategy[F, S, E] {

        override def recoverState(initial: Versioned[S]): F[Versioned[S]] =
          for {
            effectiveInitial <- snapshotting.load(key).map(_.getOrElse(initial))
            out <- inner.recoverState(effectiveInitial)
          } yield out

        override def runAction[A](current: Versioned[S],
                                  action: ActionT[F, S, E, A]): F[(Versioned[S], A)] =
          for {
            (next, a) <- inner.runAction(current, action)
            _ <- snapshotting.snapshotIfNeeded(key, current.version, next)
          } yield (next, a)

        override def updateState(current: Versioned[S], es: NonEmptyChain[E]): F[Versioned[S]] =
          for {
            next <- inner.updateState(current, es)
            _ <- snapshotting.snapshotIfNeeded(key, current.version, next)
          } yield next
      }
    }

  def withInmemCache(implicit F: Sync[F]): InMemCachingStateStrategy[K, F, S, E] =
    InMemCachingStateStrategy(this)
}
