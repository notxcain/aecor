package aecor.runtime.eventsourced

import aecor.data.{ ActionT, Folded }
import aecor.runtime.Eventsourced.Versioned
import aecor.runtime.{ EventJournal, Snapshotting }
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import cats.{ Monad, ~> }

object ActionRunner {
  def apply[F[_]: Monad, G[_]: Sync, K, S, E](key: K,
                                              create: S,
                                              update: (S, E) => Folded[S],
                                              journal: EventJournal[G, K, E],
                                              snapshotting: Snapshotting[F, K, S],
                                              journalBoundary: G ~> F): ActionT[G, S, E, ?] ~> F = {
    val es = EventsourcedState(create, update, journal)
    Lambda[ActionT[G, S, E, ?] ~> F] { action =>
      for {
        snapshot <- snapshotting.load(key)
        (before, (after, a)) <- journalBoundary {
                                 es.recover(key, snapshot)
                                   .flatMap { state =>
                                     es.run(key, state, action)
                                       .map(out => (state, out))
                                   }
                               }
        _ <- snapshotting.snapshot(key, before, after)
      } yield a
    }
  }

  def createCached[F[_]: Sync, K, S, E](
    key: K,
    create: S,
    update: (S, E) => Folded[S],
    journal: EventJournal[F, K, E],
    snapshotting: Snapshotting[F, K, S]
  ): F[ActionT[F, S, E, ?] ~> F] =
    Ref[F].of(none[Versioned[S]]).map { cache =>
      val es = EventsourcedState(create, update, journal)
      Lambda[ActionT[F, S, E, ?] ~> F] { action =>
        for {
          (after, a) <- cache.get.flatMap {
                         case Some(before) =>
                           es.run(key, before, action)
                             .flatTap {
                               case (after, _) =>
                                 snapshotting.snapshot(key, before, after)
                             }
                         case None =>
                           snapshotting
                             .load(key)
                             .flatMap(es.recover(key, _))
                             .flatMap { before =>
                               es.run(key, before, action)
                                 .flatTap {
                                   case (after, _) =>
                                     snapshotting.snapshot(key, before, after)
                                 }
                             }
                       }
          _ <- cache.set(after.some)
        } yield a
      }
    }

}
