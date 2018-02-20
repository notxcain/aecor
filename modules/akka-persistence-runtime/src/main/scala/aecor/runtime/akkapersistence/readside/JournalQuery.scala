package aecor.runtime.akkapersistence.readside

import aecor.data.{ EntityEvent, EventTag, TagConsumer }
import aecor.util.KeyValueStore
import akka.NotUsed
import akka.stream.scaladsl.Source
import cats.effect.Effect

final case class JournalEntry[O, I, A](offset: O, event: EntityEvent[I, A]) {
  def map[B](f: A => B): JournalEntry[O, I, B] = copy(event = event.map(f))
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
