package aecor.runtime.akkapersistence.readside

import aecor.Has
import aecor.data.{ EntityEvent, EventTag, TagConsumer }
import aecor.runtime.KeyValueStore
import akka.NotUsed
import akka.stream.scaladsl.Source
import cats.effect.Effect

final case class JournalEntry[O, K, A](offset: O, event: EntityEvent[K, A]) {
  def map[B](f: A => B): JournalEntry[O, K, B] = copy(event = event.map(f))
}

object JournalEntry {
  implicit def aecorHasInstanceForEvent[X, O, I, A](
    implicit A: Has[X, EntityEvent[I, A]]
  ): Has[X, JournalEntry[O, I, A]] =
    Has.instance[JournalEntry[O, I, A]](x => A.get(x.event))
  implicit def aecorHasInstanceForOffset[X, O, I, A](
    implicit A: Has[X, O]
  ): Has[X, JournalEntry[O, I, A]] =
    Has.instance[JournalEntry[O, I, A]](x => A.get(x.offset))
}

trait JournalQuery[Offset, I, E] {
  def eventsByTag(tag: EventTag,
                  offset: Option[Offset]): Source[JournalEntry[Offset, I, E], NotUsed]

  def currentEventsByTag(tag: EventTag,
                         offset: Option[Offset]): Source[JournalEntry[Offset, I, E], NotUsed]

  def committable[F[_]: Effect](
    offsetStore: KeyValueStore[F, TagConsumer, Offset]
  ): CommittableEventJournalQuery[F, Offset, I, E] =
    new CommittableEventJournalQuery(this, offsetStore)
}
