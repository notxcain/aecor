package aecor.runtime

import aecor.data._
import aecor.runtime.eventsourced.{ ActionRunner, EventsourcedState }
import cats.arrow.FunctionK
import cats.{ Applicative, Functor, Monad, ~> }
import cats.effect.Sync
import cats.implicits._
import cats.tagless.FunctorK
import cats.tagless.implicits._
object Eventsourced {
  def cached[M[_[_]]: FunctorK, F[_]: Sync, S, E, K](behavior: EventsourcedBehavior[M, F, S, E],
                                                     journal: EventJournal[F, K, E],
                                                     snapshotting: Option[Snapshotting[F, K, S]] =
                                                       none): K => F[M[F]] = {
    val strategy = EventsourcedState(behavior.create, behavior.update, journal)
    val effectiveSnapshotting = snapshotting.getOrElse(Snapshotting.noSnapshot[F, K, S])

    { key =>
      ActionRunner
        .cached(key, strategy, effectiveSnapshotting)
        .map(behavior.actions.mapK(_))
    }
  }

  def apply[M[_[_]]: FunctorK, F[_]: Sync, S, E, K](
    behavior: EventsourcedBehavior[M, F, S, E],
    journal: EventJournal[F, K, E],
    snapshotting: Option[Snapshotting[F, K, S]] = none
  ): K => M[F] = Eventsourced(behavior, journal, FunctionK.id, snapshotting)

  def apply[M[_[_]]: FunctorK, F[_]: Monad, G[_]: Sync, S, E, K](
    behavior: EventsourcedBehavior[M, G, S, E],
    journal: EventJournal[G, K, E],
    journalBoundary: G ~> F,
    snapshotting: Option[Snapshotting[F, K, S]] = none
  ): K => M[F] = {
    val strategy = EventsourcedState(behavior.create, behavior.update, journal)
    val effectiveSnapshotting = snapshotting.getOrElse(Snapshotting.noSnapshot[F, K, S])

    { key =>
      behavior.actions.mapK(ActionRunner(key, strategy, effectiveSnapshotting, journalBoundary))
    }
  }

  type Entities[K, M[_[_]], F[_]] = K => M[F]

  object Entities {
    type Rejectable[K, M[_[_]], F[_], R] = Entities[K, M, λ[α => F[Either[R, α]]]]

    def apply[K, M[_[_]], F[_]](kmf: K => M[F]): Entities[K, M, F] = kmf

    def fromEitherK[K, M[_[_]]: FunctorK, F[_], R](
      mfr: K => EitherK[M, R, F]
    ): Rejectable[K, M, F, R] = k => mfr(k).unwrap

  }

  final class SnapshotEach[F[_]: Applicative, K, S](interval: Long,
                                                    store: KeyValueStore[F, K, Versioned[S]])
      extends Snapshotting[F, K, S] {
    def snapshotIfNeeded(key: K, currentState: Versioned[S], nextState: Versioned[S]): F[Unit] =
      if (nextState.version % interval == 0 || ((currentState.version / interval) - (nextState.version / interval)) > 0)
        store.setValue(key, nextState)
      else
        ().pure[F]
    override def load(k: K): F[Option[Versioned[S]]] =
      store.getValue(k)
  }

  final class NoSnapshot[F[_]: Applicative, K, S] extends Snapshotting[F, K, S] {
    override def snapshotIfNeeded(key: K,
                                  currentState: Versioned[S],
                                  nextState: Versioned[S]): F[Unit] =
      ().pure[F]
    override def load(k: K): F[Option[Versioned[S]]] =
      none[Versioned[S]].pure[F]
  }

  trait Snapshotting[F[_], K, S] {
    def snapshotIfNeeded(key: K, currentState: Versioned[S], nextState: Versioned[S]): F[Unit]
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

  final case class Versioned[A](version: Long, value: A) {
    def withNextValue(s: A): Versioned[A] = Versioned(version + 1L, value)
  }
  object Versioned {
    def zero[A](a: A): Versioned[A] = Versioned(0L, a)
    def update[F[_]: Functor, S, E](f: (S, E) => F[S]): (Versioned[S], E) => F[Versioned[S]] = {
      case (Versioned(version, s), e) =>
        f(s, e).map(Versioned(version + 1, _))
    }
  }

  type EntityKey = String

  sealed abstract class BehaviorFailure extends Throwable with Product with Serializable
  object BehaviorFailure {
    def illegalFold(entityKey: EntityKey): BehaviorFailure = IllegalFold(entityKey)
    final case class IllegalFold(entityKey: EntityKey) extends BehaviorFailure
  }
}
