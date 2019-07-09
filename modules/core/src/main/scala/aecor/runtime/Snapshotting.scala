package aecor.runtime

import aecor.runtime.Eventsourced.Versioned
import cats.Applicative
import cats.implicits.none
import cats.implicits._

final class SnapshotEach[F[_]: Applicative, K, S](interval: Long,
                                                  store: KeyValueStore[F, K, Versioned[S]])
    extends Snapshotting[F, K, S] {
  def snapshot(key: K, currentState: Versioned[S], nextState: Versioned[S]): F[Unit] =
    if (nextState.version % interval == 0 || ((currentState.version / interval) - (nextState.version / interval)) > 0)
      store.setValue(key, nextState)
    else
      ().pure[F]
  override def load(k: K): F[Option[Versioned[S]]] =
    store.getValue(k)
}

final class NoSnapshot[F[_]: Applicative, K, S] extends Snapshotting[F, K, S] {
  private val void = ().pure[F]
  private val empty = none[Versioned[S]].pure[F]
  override def snapshot(key: K, currentState: Versioned[S], nextState: Versioned[S]): F[Unit] =
    void
  override def load(k: K): F[Option[Versioned[S]]] =
    empty
}

trait Snapshotting[F[_], K, S] {
  def snapshot(key: K, before: Versioned[S], after: Versioned[S]): F[Unit]
  def load(k: K): F[Option[Versioned[S]]]
}

object Snapshotting {
  def eachVersion[F[_]: Applicative, K, S](
    interval: Long,
    store: KeyValueStore[F, K, Versioned[S]]
  ): Snapshotting[F, K, S] =
    new SnapshotEach(interval, store)

  def disabled[F[_]: Applicative, K, S]: Snapshotting[F, K, S] = new NoSnapshot
}
