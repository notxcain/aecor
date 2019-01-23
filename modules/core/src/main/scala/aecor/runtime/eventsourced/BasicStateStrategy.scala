package aecor.runtime.eventsourced
import aecor.data.Folded
import aecor.data.Folded.{ Impossible, Next }
import aecor.runtime.EventJournal
import aecor.runtime.Eventsourced.{ BehaviorFailure, Snapshotting, Versioned }
import cats.MonadError
import cats.data.NonEmptyChain
import cats.effect.Sync
import cats.implicits._

final class BasicStateStrategy[F[_], K, S, E](val key: K, journal: EventJournal[F, K, E])(
  implicit F: MonadError[F, Throwable]
) extends StateStrategy[F, K, S, E] {

  override def getState(initial: Versioned[S],
                        fold: (Versioned[S], E) => Folded[Versioned[S]]): F[Versioned[S]] =
    journal
      .foldById(key, initial.version + 1, initial)(fold)
      .flatMap {
        case Next(x) => x.pure[F]
        case Impossible =>
          F.raiseError[Versioned[S]](
            BehaviorFailure
              .illegalFold(key.toString)
          )
      }

  override def updateState(currentVersion: Long,
                           nextState: Versioned[S],
                           es: NonEmptyChain[E]): F[Unit] =
    journal.append(key, currentVersion + 1, es)

  def withSnapshotting(snapshotting: Snapshotting[F, K, S]): SnapshottingStateStrategy[F, K, S, E] =
    SnapshottingStateStrategy(snapshotting, this)

  def withInmemCache(implicit F: Sync[F]): F[InMemCachingStateStrategy[F, K, S, E]] =
    InMemCachingStateStrategy.create(this)
}

object BasicStateStrategy {
  def apply[F[_], K, S, E](key: K, journal: EventJournal[F, K, E])(
    implicit F: MonadError[F, Throwable]
  ): BasicStateStrategy[F, K, S, E] = new BasicStateStrategy(key, journal)
}
