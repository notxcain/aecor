package aecor.runtime

import aecor.data._
import cats.arrow.FunctionK
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import cats.tagless.FunctorK
import cats.tagless.implicits._
import cats.{ Applicative, Functor, Monad, ~> }

object Eventsourced {
  def createCached[M[_[_]]: FunctorK, F[_]: Sync, S, E, K](
    behavior: EventsourcedBehavior[M, F, S, E],
    journal: EventJournal[F, K, E],
    snapshotting: Snapshotting[F, K, S]
  ): K => F[M[F]] = {
    val es = EventsourcedState(behavior.fold, journal)

    { key =>
      Ref[F].of(none[Versioned[S]]).map { cache =>
        behavior.actions.mapK(Lambda[ActionT[F, S, E, *] ~> F] { action =>
          for {
            before <- cache.get.flatMap {
                       case Some(before) => before.pure[F]
                       case None =>
                         snapshotting
                           .load(key)
                           .flatMap(es.recover(key, _))
                     }
            (after, a) <- es
                           .run(key, before, action)
                           .flatTap {
                             case (after, _) =>
                               snapshotting.snapshot(key, before, after)
                           }
            _ <- cache.set(after.some)
          } yield a
        })
      }

    }
  }

  def createCached[M[_[_]]: FunctorK, F[_]: Sync, S, E, K](
    behavior: EventsourcedBehavior[M, F, S, E],
    journal: EventJournal[F, K, E]
  ): K => F[M[F]] = createCached(behavior, journal, Snapshotting.disabled[F, K, S])

  def apply[M[_[_]]: FunctorK, F[_]: Monad, G[_]: Sync, S, E, K](
    behavior: EventsourcedBehavior[M, G, S, E],
    journal: EventJournal[G, K, E],
    journalBoundary: G ~> F,
    snapshotting: Snapshotting[F, K, S]
  ): K => M[F] = {
    val es = EventsourcedState(behavior.fold, journal)

    { key =>
      behavior.actions.mapK {
        Lambda[ActionT[G, S, E, *] ~> F] { action =>
          for {
            snapshot <- snapshotting.load(key)
            (before, (after, a)) <- journalBoundary {
                                     es.recover(key, snapshot)
                                       .flatMap { state =>
                                         es.run(key, state, action)
                                           .map((state, _))
                                       }
                                   }
            _ <- snapshotting.snapshot(key, before, after)
          } yield a
        }
      }
    }
  }

  def apply[M[_[_]]: FunctorK, F[_]: Sync, S, E, K](behavior: EventsourcedBehavior[M, F, S, E],
                                                    journal: EventJournal[F, K, E],
  ): K => M[F] = apply(behavior, journal, FunctionK.id, Snapshotting.disabled[F, K, S])

  type Entities[K, M[_[_]], F[_]] = K => M[F]

  object Entities {
    type Rejectable[K, M[_[_]], F[_], R] = Entities[K, M, λ[α => F[Either[R, α]]]]

    def apply[K, M[_[_]], F[_]](kmf: K => M[F]): Entities[K, M, F] = kmf

    def rejectable[K, M[_[_]]: FunctorK, F[_], R](
      mfr: K => EitherK[M, R, F]
    ): Rejectable[K, M, F, R] = k => mfr(k).unwrap

    @deprecated("Use rejectable", "0.19.0")
    def fromEitherK[K, M[_[_]]: FunctorK, F[_], R](
      mfr: K => EitherK[M, R, F]
    ): Rejectable[K, M, F, R] = rejectable(mfr)
  }

  final case class Versioned[A](version: Long, value: A) {
    def traverse[F[_], B](f: A => F[B])(implicit F: Functor[F]): F[Versioned[B]] =
      f(value).map(Versioned(version, _))
    def map[B](f: A => B): Versioned[B] = Versioned(version, f(value))
  }

  object Versioned {
    def fold[F[_]: Applicative, E, S](inner: Fold[F, S, E]): Fold[F, Versioned[S], E] =
      Fold.count[F, E].andWith(inner)(Versioned(_, _), v => (v.version, v.value))
  }
}
