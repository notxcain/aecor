package aecor.runtime.akkapersistence.readside

import aecor.Has
import aecor.data.{ EntityEvent, EventTag, TagConsumer }
import aecor.runtime.KeyValueStore
import akka.NotUsed
import akka.stream.scaladsl.Source
import cats.effect.kernel.Async
import cats.effect.std.Dispatcher
import cats.syntax.functor._

final case class JournalEntry[O, K, A](offset: O, event: EntityEvent[K, A]) {
  def map[B](f: A => B): JournalEntry[O, K, B] = copy(event = event.map(f))
}

object JournalEntry {
  implicit def aecorHasInstanceForEvent[X, O, K, A](implicit
      A: Has[EntityEvent[K, A], X]
  ): Has[JournalEntry[O, K, A], X] =
    A.contramap(_.event)

  implicit def aecorHasInstanceForOffset[X, O, K, A](implicit
      A: Has[O, X]
  ): Has[JournalEntry[O, K, A], X] = A.contramap(_.offset)

}

trait JournalQuery[O, K, E] {
  def eventsByTag(tag: EventTag, offset: Option[O]): Source[JournalEntry[O, K, E], NotUsed]

  def currentEventsByTag(tag: EventTag, offset: Option[O]): Source[JournalEntry[O, K, E], NotUsed]

  def committable[F[_]: Async](
      offsetStore: KeyValueStore[F, TagConsumer, O]
  ): F[CommittableEventJournalQuery[F, O, K, E]] =
    Dispatcher[F].allocated
      .map(_._1)
      .map(dispatcher => new CommittableEventJournalQuery(this, offsetStore, dispatcher))
}
