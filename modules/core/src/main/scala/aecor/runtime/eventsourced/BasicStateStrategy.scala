package aecor.runtime.eventsourced
import aecor.data.{ ActionT, Folded }
import aecor.data.Folded.{ Impossible, Next }
import aecor.runtime.EventJournal
import aecor.runtime.Eventsourced.{ BehaviorFailure, Snapshotting, Versioned }
import cats.MonadError
import cats.data.NonEmptyChain
import cats.effect.Sync
import cats.implicits._

final class BasicStateStrategy[F[_], K, S, E](
  val key: K,
  initial: S,
  update: (S, E) => Folded[S],
  journal: EventJournal[F, K, E]
)(implicit F: MonadError[F, Throwable])
    extends StateStrategy[F, K, S, E] {

  override def getState(initial: Versioned[S]): F[Versioned[S]] =
    journal
      .foldById(key, initial.version + 1, initial) { (s: Versioned[S], e: E) =>
        update(s.value, e)
          .map(Versioned(_, s.version + 1))
      }
      .flatMap(unfold)

  def unfold[A](f: Folded[A]): F[A] = f match {
    case Next(x) => x.pure[F]
    case Impossible =>
      F.raiseError[A](
        BehaviorFailure
          .illegalFold(key.toString)
      )
  }

  override def updateState(current: Versioned[S], es: NonEmptyChain[E]): F[Versioned[S]] =
    unfold(es.foldLeftM(current.value)(update))
      .map(Versioned(_, current.version + es.size))
      .flatTap(_ => journal.append(key, current.version + 1, es))

  override def run[A](action: ActionT[F, S, E, A]): F[A] =
    for {
      current <- getState(Versioned(initial, 0))
      result <- action.run(current.value, update)
      (es, a) <- unfold(result)
      _ <- NonEmptyChain
            .fromChain(es)
            .traverse_(nes => updateState(current, nes))
    } yield a

  def withSnapshotting(snapshotting: Snapshotting[F, K, S]): SnapshottingStateStrategy[F, K, S, E] =
    SnapshottingStateStrategy(snapshotting, this)

  def withInmemCache(implicit F: Sync[F]): F[InMemCachingStateStrategy[F, K, S, E]] =
    InMemCachingStateStrategy.create(this)
}

object BasicStateStrategy {
  def apply[F[_], K, S, E](
    key: K,
    initial: S,
    update: (S, E) => Folded[S],
    journal: EventJournal[F, K, E]
  )(implicit F: MonadError[F, Throwable]): BasicStateStrategy[F, K, S, E] =
    new BasicStateStrategy(key, initial, update, journal)
}
