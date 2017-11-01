package aecor.runtime.akkapersistence.readside

import aecor.data.Folded
import aecor.runtime.akkapersistence.JournalEntry
import cats.MonadError
import cats.implicits._

object Projection {
  final case class Input[I, E](entityId: I, sequenceNr: Long, event: E)
  object Input {
    def fromJournalEntry[O, I, A](journalEntry: JournalEntry[O, I, A]): Input[I, A] =
      Input(journalEntry.entityId, journalEntry.sequenceNr, journalEntry.event)
  }
  def apply[F[_]: MonadError[?[_], Throwable], I, E, S](
    store: Store[F, I, Versioned[S]],
    zero: E => Folded[S],
    update: (E, S) => Folded[S]
  ): Input[I, E] => F[Unit] = {
    case Input(id, seqNr, e) =>
      def error[A](message: String): F[A] =
        (new RuntimeException("Illegal fold"): Throwable).raiseError[F, A]
      for {
        oldStateOpt <- store.readState(id)
        currentVersion = oldStateOpt.fold(0L)(_.version)
        _ <- if (seqNr <= currentVersion) {
              ().pure[F]
            } else if (seqNr == currentVersion + 1) {
              oldStateOpt
                .map(_.a)
                .fold(zero(e))(update(e, _))
                .fold(error[Unit]("Illegal fold")) { a =>
                  store.saveState(id, Versioned(currentVersion + 1, a))
                }
            } else {
              error[Unit]("Missing event")
            }
      } yield ()
  }
}

trait Store[F[_], I, S] {
  def readState(i: I): F[Option[S]]
  def saveState(i: I, s: S): F[Unit]
}

case class Versioned[A](version: Long, a: A)
