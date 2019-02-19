package aecor.runtime.eventsourced
import aecor.runtime.Eventsourced.{ Snapshotting, Versioned }
import cats.Monad
import cats.data.NonEmptyChain
import cats.effect.Sync
import cats.implicits._

final class SnapshottingStateStrategy[F[_]: Monad, K, S, E](key: K,
                                                            snapshotting: Snapshotting[F, K, S],
                                                            inner: StateStrategy[F, S, E])
    extends StateStrategy[F, S, E] {

  override def recoverState(initial: Versioned[S]): F[Versioned[S]] =
    for {
      effectiveInitial <- snapshotting.load(key).map(_.getOrElse(initial))
      out <- inner.recoverState(effectiveInitial)
    } yield out

  override def updateState(current: Versioned[S], es: NonEmptyChain[E]): F[Versioned[S]] =
    for {
      next <- inner.updateState(current, es)
      _ <- snapshotting.snapshotIfNeeded(key, current.version, next)
    } yield next

  def withInmemCache(implicit F: Sync[F]): F[InMemCachingStateStrategy[F, S, E]] =
    InMemCachingStateStrategy.create(this)
}

object SnapshottingStateStrategy {
  def apply[F[_]: Monad, K, S, E](
    key: K,
    snapshotting: Snapshotting[F, K, S],
    inner: StateStrategy[F, S, E]
  ): SnapshottingStateStrategy[F, K, S, E] =
    new SnapshottingStateStrategy(key, snapshotting, inner)
}
