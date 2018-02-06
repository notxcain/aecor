package aecor.runtime.akkapersistence.readside

import aecor.data.Folded
import aecor.runtime.akkapersistence.JournalEntry
import cats.MonadError
import cats.implicits._

object Projection {
  trait ProjectionError[A, E, S] {
    def illegalFold(event: E, state: S): A
    def missingEvent(event: E, state: S): A
  }
  final case class Input[I, E](entityId: I, sequenceNr: Long, event: E)
  object Input {
    def fromJournalEntry[O, I, A](journalEntry: JournalEntry[O, I, A]): Input[I, A] =
      Input(journalEntry.entityId, journalEntry.sequenceNr, journalEntry.event)
  }
  def apply[F[_], E, Key, Event, State](store: Store[F, Key, Versioned[State]],
                                        zero: Event => Folded[State],
                                        update: (Event, State) => Folded[State])(
    implicit F: MonadError[F, E],
    Error: ProjectionError[E, Input[Key, Event], Option[Versioned[State]]]
  ): Input[Key, Event] => F[Unit] = {
    case input @ Input(id, seqNr, event) =>
      for {
        state <- store.readState(id)
        currentVersion = state.fold(0L)(_.version)
        _ <- if (seqNr <= currentVersion) {
              ().pure[F]
            } else if (seqNr == currentVersion + 1) {
              state
                .map(_.a)
                .fold(zero(event))(update(event, _))
                .fold(F.raiseError[Unit](Error.illegalFold(input, state))) { a =>
                  store.saveState(id, Versioned(currentVersion + 1, a))
                }
            } else {
              F.raiseError(Error.missingEvent(input, state))
            }
      } yield ()
  }
}

trait Store[F[_], I, S] {
  def readState(i: I): F[Option[S]]
  def saveState(i: I, s: S): F[Unit]
}

case class Versioned[A](version: Long, a: A)
