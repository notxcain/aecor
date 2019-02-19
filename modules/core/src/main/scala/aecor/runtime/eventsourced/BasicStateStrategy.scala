package aecor.runtime.eventsourced
import aecor.data.Folded
import aecor.runtime.EventJournal
import aecor.runtime.Eventsourced.{ Snapshotting, Versioned }
import cats.data.NonEmptyChain
import cats.effect.Sync
import cats.implicits._
import cats.{ Monad, ~> }

final class BasicStateStrategy[F[_]: Monad, K, S, E](key: K,
                                                     initial: S,
                                                     update: (S, E) => Folded[S],
                                                     journal: EventJournal[F, K, E],
                                                     unfold: Folded ~> F)
    extends StateStrategy[F, S, E] {

  override def recoverState(current: Versioned[S]): F[Versioned[S]] =
    journal
      .foldById(key, current.version + 1, current) {
        case (Versioned(version, value), event) =>
          update(value, event)
            .map(Versioned(version + 1, _))
      }
      .flatMap(unfold(_))

  override def updateState(current: Versioned[S], es: NonEmptyChain[E]): F[Versioned[S]] =
    unfold(es.foldLeftM(current.value)(update))
      .map(Versioned(_, current.version + es.size))
      .flatTap(_ => journal.append(key, current.version + 1, es))

  def withSnapshotting(snapshotting: Snapshotting[F, K, S]): SnapshottingStateStrategy[F, K, S, E] =
    SnapshottingStateStrategy(key, snapshotting, this)

  def withInmemCache(implicit F: Sync[F]): F[InMemCachingStateStrategy[F, S, E]] =
    InMemCachingStateStrategy.create(this)
}
