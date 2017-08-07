package aecor.runtime.akkapersistence

import aecor.data.{ EventTag, TagConsumer }
import aecor.effect.Async
import aecor.util.KeyValueStore
import akka.NotUsed
import akka.stream.scaladsl.Source

final case class JournalEntry[+O, +A](offset: O, persistenceId: String, sequenceNr: Long, event: A)

trait EventJournalQuery[Offset, E] {
  def eventsByTag(tag: EventTag, offset: Option[Offset]): Source[JournalEntry[Offset, E], NotUsed]

  def currentEventsByTag(tag: EventTag,
                         offset: Option[Offset]): Source[JournalEntry[Offset, E], NotUsed]

  def committable[F[_]: Async](
    offsetStore: KeyValueStore[F, TagConsumer, Offset]
  ): CommittableEventJournalQuery[F, Offset, E] =
    new CommittableEventJournalQuery(this, offsetStore)
}
