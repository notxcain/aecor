package aecor.runtime.akkapersistence

import aecor.data.{ EventTag, Identified, TagConsumer }
import aecor.effect.Async
import aecor.util.KeyValueStore
import akka.NotUsed
import akka.stream.scaladsl.Source

final case class JournalEntry[+O, +I, +A](offset: O, entityId: I, sequenceNr: Long, event: A) {
  def identified: Identified[I, A] = Identified(entityId, event)
  def map[B](f: A => B): JournalEntry[O, I, B] = copy(event = f(event))
}

trait EventJournal[Offset, I, E] {
  def eventsByTag(tag: EventTag,
                  offset: Option[Offset]): Source[JournalEntry[Offset, I, E], NotUsed]

  def currentEventsByTag(tag: EventTag,
                         offset: Option[Offset]): Source[JournalEntry[Offset, I, E], NotUsed]

  def committable[F[_]: Async](
    offsetStore: KeyValueStore[F, TagConsumer, Offset]
  ): CommittableEventJournalQuery[F, Offset, I, E] =
    new CommittableEventJournalQuery(this, offsetStore)
}
