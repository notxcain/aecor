package aecor.runtime.akkapersistence

import aecor.data.Folded.{ Impossible, Next }
import aecor.data.{ Committable, Folded }
import aecor.effect.Async
import aecor.effect.Async.ops._
import aecor.util.KeyValueStore
import akka.NotUsed
import akka.stream.scaladsl.Flow
import cats.{ Foldable, Monad, MonadError }
import cats.implicits._

final case class VersionedState[A](version: Long, a: A) {
  def next(a: A): VersionedState[A] = VersionedState(version + 1, a)
}
object VersionedState {
  def first[A](a: A): VersionedState[A] = VersionedState(1, a)
  implicit def projection[O, E, A](
    implicit A: AggregateProjection[E, A]
  ): AggregateProjection[JournalEntry[O, E], VersionedState[A]] =
    new AggregateProjection[JournalEntry[O, E], VersionedState[A]] {
      override def reduce(os: Option[VersionedState[A]],
                          entry: JournalEntry[O, E]): Folded[VersionedState[A]] = {
        val currentVersion = os.map(_.version).getOrElse(0L)
        if (currentVersion < entry.sequenceNr) {
          A.reduce(os.map(_.a), entry.event).map { next =>
            os.map(_.next(next))
              .getOrElse(first(next))
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
  def flow[F[_]: Async: MonadError[?[_], Throwable], E, S](
    store: KeyValueStore[F, E, S]
  )(implicit S: AggregateProjection[E, S]): Flow[Committable[F, E], Unit, NotUsed] =
    Flow[Committable[F, E]]
      .map { c =>
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
      .mapAsync(1)(_.unsafeRun)
}
