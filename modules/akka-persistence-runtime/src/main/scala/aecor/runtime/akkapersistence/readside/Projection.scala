package aecor.runtime.akkapersistence.readside

import aecor.data.{ EntityEvent, Folded }
import aecor.runtime.KeyValueStore
import cats.Monad
import cats.implicits._

object Projection {
  final case class Versioned[A](version: Long, a: A)

  trait Failure[F[_], K, E, S] {
    def illegalFold[A](event: EntityEvent[K, E], state: Option[S]): F[A]
    def missingEvent[A](key: K, seqNr: Long): F[A]
  }

  def apply[F[_]: Monad, Key, Event, State](
    store: KeyValueStore[F, Key, Versioned[State]],
    zero: Event => Folded[State],
    update: (Event, State) => Folded[State],
    failure: Failure[F, Key, Event, State]
  ): EntityEvent[Key, Event] => F[Unit] = {
    case input @ EntityEvent(key, seqNr, event) =>
      for {
        state <- store.getValue(key)
        currentVersion = state.fold(0L)(_.version)
        _ <- if (seqNr <= currentVersion) {
              ().pure[F]
            } else if (seqNr == currentVersion + 1) {
              state
                .map(_.a)
                .fold(zero(event))(update(event, _))
                .fold(failure.illegalFold[Unit](input, state.map(_.a))) { a =>
                  store.setValue(key, Versioned(currentVersion + 1, a))
                }
            } else {
              failure.missingEvent(key, currentVersion + 1)
            }
      } yield ()
  }
}
