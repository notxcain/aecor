package aecor.runtime.akkapersistence

import aecor.data.Folded.{ Impossible, Next }
import aecor.data.{ Committable, Folded }
import aecor.effect.Async
import aecor.util.KeyValueStore
import cats.MonadError
import cats.implicits._

final case class Versioned[A](version: Long, a: A) {
  def update(a: A): Versioned[A] = Versioned(version + 1, a)
}
object Versioned {
  def first[A](a: A): Versioned[A] = Versioned(1, a)
  implicit def projection[O, E, A](
    implicit A: AggregateProjection[E, A]
  ): AggregateProjection[JournalEntry[O, E], Versioned[A]] =
    new AggregateProjection[JournalEntry[O, E], Versioned[A]] {
      override def reduce(os: Option[Versioned[A]],
                          entry: JournalEntry[O, E]): Folded[Versioned[A]] = {
        val currentVersion = os.map(_.version).getOrElse(0L)
        if (currentVersion < entry.sequenceNr) {
          A.reduce(os.map(_.a), entry.event).map { a =>
            os.map(_.update(a))
              .getOrElse(first(a))
          }
        } else {
          os.map(Folded.next).getOrElse(Folded.impossible)
        }
      }
    }
}

trait AggregateProjection[E, S] {
  def reduce(s: Option[S], event: E): Folded[S]
}

object AggregateProjection {
  def instance[S, E](f: Option[S] => E => Folded[S]): AggregateProjection[E, S] =
    new AggregateProjection[E, S] {
      override def reduce(s: Option[S], event: E): Folded[S] = f(s)(event)
    }
  def step[F[_]: Async: MonadError[?[_], Throwable], E, S](
    store: KeyValueStore[F, E, S]
  )(implicit S: AggregateProjection[E, S]): Committable[F, E] => F[Unit] = { c =>
    c.traverse { event =>
        store.getValue(event).flatMap { currentState =>
          S.reduce(currentState, event) match {
            case Next(nextState) =>
              if (currentState.contains(nextState))
                ().pure[F]
              else
                store.setValue(event, nextState)
            case Impossible =>
              val error: Throwable =
                new IllegalStateException(
                  s"Projection failed for state = [$currentState], event = [$event]"
                )
              error.raiseError[F, Unit]
          }
        }
      }
      .flatMap(_.commit)
  }

}
