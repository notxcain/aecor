package aecor.runtime

import aecor.data._
import aecor.runtime.eventsourced.StateStrategy
import cats.Applicative
import cats.effect.Sync
import cats.implicits._
import cats.tagless.FunctorK

object Eventsourced {
  def apply[M[_[_]]: FunctorK, F[_]: Sync, S, E, K](behavior: EventsourcedBehavior[M, F, S, E],
                                                    journal: EventJournal[F, K, E],
                                                    snapshotting: Option[Snapshotting[F, K, S]] =
                                                      none): K => F[M[F]] = { key =>
    StateStrategy
      .eventsourced[F, K, S, E](key, behavior.initial, behavior.update, journal)
      .withSnapshotting(snapshotting.getOrElse(Snapshotting.noSnapshot[F, K, S]))
      .withInmemCache
      .map(_.runActions(behavior.actions))
  }

  sealed abstract class Entities[K, M[_[_]], F[_]] {
    def apply(k: K): M[F]
  }

  object Entities {
    type Rejectable[K, M[_[_]], F[_], R] = Entities[K, M, λ[α => F[Either[R, α]]]]

    def apply[K, M[_[_]], F[_]](kmf: K => M[F]): Entities[K, M, F] = new Entities[K, M, F] {
      override def apply(k: K): M[F] = kmf(k)
    }

    def fromEitherK[K, M[_[_]]: FunctorK, F[_], R](
      mfr: K => EitherK[M, R, F]
    ): Rejectable[K, M, F, R] =
      new Rejectable[K, M, F, R] {
        override def apply(k: K): M[λ[α => F[Either[R, α]]]] = mfr(k).unwrap
      }
  }

  final class SnapshotEach[F[_]: Applicative, K, S](interval: Long,
                                                    store: KeyValueStore[F, K, Versioned[S]])
      extends Snapshotting[F, K, S] {
    def snapshotIfNeeded(key: K, currentVersion: Long, nextState: Versioned[S]): F[Unit] =
      if (nextState.version % interval == 0 || ((currentVersion / interval) - (nextState.version / interval)) > 0)
        store.setValue(key, nextState)
      else
        ().pure[F]
    override def load(k: K): F[Option[Versioned[S]]] =
      store.getValue(k)
  }

  final class NoSnapshot[F[_]: Applicative, K, S] extends Snapshotting[F, K, S] {
    override def snapshotIfNeeded(key: K, currentVersion: Long, nextState: Versioned[S]): F[Unit] =
      ().pure[F]
    override def load(k: K): F[Option[Versioned[S]]] =
      none[Versioned[S]].pure[F]
  }

  trait Snapshotting[F[_], K, S] {
    def snapshotIfNeeded(key: K, currentVersion: Long, nextState: Versioned[S]): F[Unit]
    def load(k: K): F[Option[Versioned[S]]]
  }

  object Snapshotting {
    def snapshotEach[F[_]: Applicative, K, S](
      interval: Long,
      store: KeyValueStore[F, K, Versioned[S]]
    ): Snapshotting[F, K, S] =
      new SnapshotEach(interval, store)

    def noSnapshot[F[_]: Applicative, K, S]: Snapshotting[F, K, S] = new NoSnapshot
  }

  final case class Versioned[A](value: A, version: Long) {
    def withValue(s: A): Versioned[A] = copy(value = s)
  }

  type EntityKey = String

  sealed abstract class BehaviorFailure extends Throwable with Product with Serializable
  object BehaviorFailure {
    def illegalFold(entityKey: EntityKey): BehaviorFailure = IllegalFold(entityKey)
    final case class IllegalFold(entityKey: EntityKey) extends BehaviorFailure
  }
}
