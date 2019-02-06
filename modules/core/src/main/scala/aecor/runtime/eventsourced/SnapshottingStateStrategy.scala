package aecor.runtime.eventsourced
import aecor.data.ActionT
import aecor.runtime.Eventsourced.{ Snapshotting, Versioned }
import cats.Monad
import cats.data.NonEmptyChain
import cats.effect.Sync
import cats.implicits._

final class SnapshottingStateStrategy[F[_]: Monad, K, S, E](snapshotting: Snapshotting[F, K, S],
                                                            inner: StateStrategy[F, K, S, E])
    extends StateStrategy[F, K, S, E] {

  override val key: K = inner.key

  override def getState(initial: Versioned[S]): F[Versioned[S]] =
    for {
      effectiveInitial <- snapshotting.load(key).map(_.getOrElse(initial))
      out <- inner.getState(effectiveInitial)
    } yield out

  override def updateState(current: Versioned[S], es: NonEmptyChain[E]): F[Versioned[S]] =
    for {
      next <- inner.updateState(current, es)
      _ <- snapshotting
            .snapshotIfNeeded(key, current.version, next)
    } yield next

  override def run[A](action: ActionT[F, S, E, A]): F[A] = inner.run(action)

  def withInmemCache(implicit F: Sync[F]): F[InMemCachingStateStrategy[F, K, S, E]] =
    InMemCachingStateStrategy.create(this)
}

object SnapshottingStateStrategy {
  def apply[F[_]: Monad, K, S, E](
    snapshotting: Snapshotting[F, K, S],
    inner: StateStrategy[F, K, S, E]
  ): SnapshottingStateStrategy[F, K, S, E] =
    new SnapshottingStateStrategy(snapshotting, inner)
}
