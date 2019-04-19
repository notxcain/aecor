package aecor.runtime

import aecor.data.Folded.Next
import aecor.data.{ ActionT, EntityEvent, Folded }
import aecor.runtime.Eventsourced.{ Snapshotting, Versioned }
import cats.Monad
import cats.data.NonEmptyChain
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._

trait Recover[F[_], In, Out] {
  def recoverAndRun(snapshot: In): F[Out]
}

final class InMemCachingRecover[F[_]: Monad, A](ref: Ref[F, Option[A]], inner: Recover[F, A, A])
    extends Recover[F, A, A] {
  override def recoverAndRun(from: A): F[Out] =
    ref.get
      .flatMap {
        case Some(a) => run(a).flatTap(x => ref.set(x._1.some)).map(_._2)
        case None    => inner.recoverAndRun(from, run)
      }
}

final class SnapshottingRecover[F[_]: Monad, K, A](key: K,
                                                   snapshotting: Snapshotting[F, K, A],
                                                   inner: Recover[F, Versioned[A], Versioned[A]])
    extends Recover[F, Versioned[A], Versioned[A]] {

  override def recoverAndRun[B](snapshot: Versioned[A], run: Versioned[A] => F[(Versioned,B]): F[B] =
    snapshotting.load(key).map(_.getOrElse(snapshot)).flatMap(inner.recoverAndRun(_, run))

}

final class JournalRecover[F[_], K, E, S](key: K,
                                          update: (S, E) => Folded[S],
                                          journal: EventJournal[F, K, E])(implicit F: Sync[F])
    extends Recover[F, Versioned[S]] {

  override def recoverAndRun[B](snapshot: Versioned[S], run: Versioned[S] => F[B]): F[B] =
    journal
      .loadEvents(key, snapshot.version)
      .scan(snapshot.asRight[Throwable]) {
        case (Right(x @ Versioned(version, value)), ee @ EntityEvent(_, _, event)) =>
          update(value, event) match {
            case Next(a) =>
              Versioned(version + 1, a).asRight[Throwable]
            case Folded.Impossible =>
              new IllegalStateException(s"Failed to apply event [$ee] to [$x]")
                .asLeft[Versioned[S]]
          }
        case (other, _) => other
      }
      .takeWhile(_.isRight, takeFailure = true)
      .compile
      .last
      .flatMap {
        case Some(x) => F.fromEither(x)
        case None    => F.pure(snapshot)
      }
      .flatMap(run)
}

trait Persister[F[_], A, B] {
  def persist(a: A): F[B]
}

final class InMemCachingPersister[F[_]: Monad, A, B](ref: Ref[F, Option[A]],
                                                     inner: Persister[F, A, B])
    extends Persister[F, A, B] {
  override def persist(a: A): F[B] =
    inner.persist(a) <* ref.set(a.some)
}

final class SnapshotPersister[F[_]: Monad, K, S, A, B](key: K,
                                                       snapshotting: Snapshotting[F, K, S],
                                                       inner: Persister[F, A, (Long, Versioned[S])])
    extends Persister[F, A, (Long, Versioned[S])] {
  override def persist(a: A): F[(Long, Versioned[S])] =
    inner.persist(a).flatTap {
      case (currentVersion, next) =>
        snapshotting.snapshotIfNeeded(key, currentVersion, next)
    }
}

final class

trait TheActionRunner[F[_], S, E] {
  def run[A](action: ActionT[F, S, E, A]): F[A]
}

final class BasicActionRunner[F[_], K, S, E](key: K,
                                             update: (S, E) => Folded[S],
                                             journal: EventJournal[F, K, E])(implicit F: Sync[F])
    extends TheActionRunner[F, S, E] {

  def loadState: F[Versioned[S]] = ???
  def persistEvents(expectedOffset: Long, events: NonEmptyChain[E]): F[Unit] = ???

  override def run[A](action: ActionT[F, S, E, A]): F[A] =
    for {
      recovered <- loadState
      result <- action.run(recovered.value, update)
      out <- result match {
              case Folded.Next((events, a)) =>
                NonEmptyChain.fromChain(events).traverse_(persistEvents(recovered.version, _)).as(a)
              case Folded.Impossible =>
                F.raiseError[A](new IllegalStateException(s"Failed to run action"))
            }
    } yield out

}


trait SnapshotStrategy[F[_], S] {
  def load(initial: S, recover: Versioned[S] => F[Versioned[S]]): F[Versioned[S]]
  def snapshot(previous: Versioned[S], current: Versioned[S]): F[Unit]
}



final class SnapshottingActionRunner[F[_], K, S, E](
  key: K,
  initial: S,
  update: (S, E) => Folded[S],
  journal: EventJournal[F, K, E],
  snapshotting: SnapshotStrategy[F, S]
)(implicit F: Sync[F])
    extends TheActionRunner[F, S, E] {

  def recover(snapshot: Versioned[S]): F[Versioned[S]] =
    journal
      .loadEvents(key, snapshot.version)
      .scan(snapshot.asRight[Throwable]) {
        case (Right(x @ Versioned(version, value)), ee @ EntityEvent(_, _, event)) =>
          update(value, event) match {
            case Next(a) =>
              Versioned(version + 1, a).asRight[Throwable]
            case Folded.Impossible =>
              new IllegalStateException(s"Failed to apply event [$ee] to [$x]")
                .asLeft[Versioned[S]]
          }
        case (other, _) => other
      }
      .takeWhile(_.isRight, takeFailure = true)
      .compile
      .last
      .flatMap {
        case Some(x) => F.fromEither(x)
        case None    => F.pure(snapshot)
      }



  def loadState: F[Versioned[S]] =
    snapshotting.load(initial, recover)


  def persistEvents(expectedOffset: Long, events: NonEmptyChain[E]): F[Unit] = ???

    override def run[A](action: ActionT[F, S, E, A]): F[A] =
    for {
      recovered <- loadState
      result <- action.zipWithRead.run(recovered.value, update)
      out <- result match {
              case Folded.Next((events, (nextState, a))) =>
                NonEmptyChain
                  .fromChain(events)
                  .traverse_ { nec =>
                    persistEvents(recovered.version, nec) >>
                      snapshotting.snapshotIfNeeded(
                        key,
                        recovered.version,
                        Versioned(recovered.version + nec.size, nextState)
                      )
                  }
                  .as(a)
              case Folded.Impossible =>
                F.raiseError[A](new IllegalStateException(s"Failed to run action"))
            }
    } yield out

}
