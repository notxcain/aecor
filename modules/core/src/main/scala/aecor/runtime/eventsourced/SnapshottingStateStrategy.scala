package aecor.runtime.eventsourced
import aecor.data.Folded
import aecor.runtime.Eventsourced.{ Snapshotting, Versioned }
import cats.Monad
import cats.data.NonEmptyChain
import cats.effect.Sync
import cats.implicits._

final class SnapshottingStateStrategy[F[_]: Monad, K, S, E](snapshotting: Snapshotting[F, K, S],
                                                            inner: StateStrategy[F, K, S, E])
    extends StateStrategy[F, K, S, E] {

  override val key: K = inner.key
  override def getState(initial: Versioned[S],
                        fold: (Versioned[S], E) => Folded[Versioned[S]]): F[Versioned[S]] =
    for {
      effectiveInitial <- snapshotting.load(key).map(_.getOrElse(initial))
      out <- inner.getState(effectiveInitial, fold)
    } yield out

  override def updateState(currentVersion: Long,
                           nextState: Versioned[S],
                           es: NonEmptyChain[E]): F[Unit] =
    for {
      _ <- inner.updateState(currentVersion, nextState, es)
      _ <- snapshotting.snapshotIfNeeded(key, currentVersion, nextState)
    } yield ()

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
