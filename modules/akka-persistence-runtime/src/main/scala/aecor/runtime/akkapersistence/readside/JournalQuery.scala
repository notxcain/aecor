package aecor.runtime.akkapersistence.readside

import aecor.data.{ EventTag, Identified, TagConsumer }
import aecor.util.KeyValueStore
import akka.NotUsed
import akka.stream.scaladsl.Source
import cats.effect.Effect

final case class JournalEntry[O, I, A](offset: O, entityId: I, sequenceNr: Long, event: A) {
  def identified: Identified[I, A] = Identified(entityId, event)
  def map[B](f: A => B): JournalEntry[O, I, B] = copy(event = f(event))
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
