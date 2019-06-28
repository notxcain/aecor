package aecor.runtime

import aecor.data._
import aecor.runtime.eventsourced.ActionRunner
import cats.arrow.FunctionK
import cats.effect.Sync
import cats.implicits._
import cats.tagless.FunctorK
import cats.tagless.implicits._
import cats.{ Functor, Monad, ~> }
object Eventsourced {
  def createCached[M[_[_]]: FunctorK, F[_]: Sync, S, E, K](
    behavior: EventsourcedBehavior[M, F, S, E],
    journal: EventJournal[F, K, E],
    snapshotting: Snapshotting[F, K, S]
  ): K => F[M[F]] = { key =>
    ActionRunner
      .createCached(key, behavior.create, behavior.update, journal, snapshotting)
      .map(behavior.actions.mapK(_))
  }

  def createCached[M[_[_]]: FunctorK, F[_]: Sync, S, E, K](
    behavior: EventsourcedBehavior[M, F, S, E],
    journal: EventJournal[F, K, E]
  ): K => F[M[F]] = { key =>
    ActionRunner
      .createCached(key, behavior.create, behavior.update, journal, Snapshotting.disabled[F, K, S])
      .map(behavior.actions.mapK(_))
  }

  def apply[M[_[_]]: FunctorK, F[_]: Monad, G[_]: Sync, S, E, K](
    behavior: EventsourcedBehavior[M, G, S, E],
    journal: EventJournal[G, K, E],
    journalBoundary: G ~> F,
    snapshotting: Snapshotting[F, K, S]
  ): K => M[F] = { key =>
    val run =
      ActionRunner(key, behavior.create, behavior.update, journal, snapshotting, journalBoundary)
    behavior.actions.mapK(run)
  }

  def apply[M[_[_]]: FunctorK, F[_]: Sync, S, E, K](behavior: EventsourcedBehavior[M, F, S, E],
                                                    journal: EventJournal[F, K, E],
  ): K => M[F] = { key =>
    val run: ActionT[F, S, E, ?] ~> F =
      ActionRunner(
        key,
        behavior.create,
        behavior.update,
        journal,
        Snapshotting.disabled[F, K, S],
        FunctionK.id
      )
    behavior.actions.mapK(run)
  }

  type Entities[K, M[_[_]], F[_]] = K => M[F]

  object Entities {
    type Rejectable[K, M[_[_]], F[_], R] = Entities[K, M, λ[α => F[Either[R, α]]]]

    def apply[K, M[_[_]], F[_]](kmf: K => M[F]): Entities[K, M, F] = kmf

    def fromEitherK[K, M[_[_]]: FunctorK, F[_], R](
      mfr: K => EitherK[M, R, F]
    ): Rejectable[K, M, F, R] = k => mfr(k).unwrap

  }

  final case class Versioned[A](version: Long, value: A) {
    def withNextValue(next: A): Versioned[A] = Versioned(version + 1L, next)
  }
  object Versioned {
    def zero[A](a: A): Versioned[A] = Versioned(0L, a)
    def update[F[_]: Functor, S, E](f: (S, E) => F[S]): (Versioned[S], E) => F[Versioned[S]] = {
      (current, e) =>
        f(current.value, e).map(current.withNextValue)
    }
  }

  type EntityKey = String

  sealed abstract class BehaviorFailure extends Throwable with Product with Serializable
  object BehaviorFailure {
    def illegalFold(entityKey: EntityKey): BehaviorFailure = IllegalFold(entityKey)
    final case class IllegalFold(entityKey: EntityKey) extends BehaviorFailure
  }
}
