package aecor.runtime.akkapersistence

import aecor.data.{ EventTag, TagConsumerId }
import aecor.effect.Async
import aecor.util.KeyValueStore
import akka.NotUsed
import akka.stream.scaladsl.Source

final case class JournalEntry[+O, +A](offset: O, persistenceId: String, sequenceNr: Long, event: A) {
  def mapOffset[I](f: O => I): JournalEntry[I, A] = copy(f(offset))
}

trait EventJournalQuery[Offset, E] {
  def eventsByTag(tag: EventTag[E],
                  offset: Option[Offset]): Source[JournalEntry[Offset, E], NotUsed]

  def currentEventsByTag(tag: EventTag[E],
                         offset: Option[Offset]): Source[JournalEntry[Offset, E], NotUsed]

  def committable[F[_]: Async](
    offsetStore: KeyValueStore[F, TagConsumerId, Offset]
  ): CommittableEventJournalQuery[F, Offset, E] =
    new CommittableEventJournalQuery(this, offsetStore)
}
