package aecor.streaming

import aecor.serialization.PersistentDecoder
import akka.NotUsed
import akka.stream.scaladsl.Source

case class CommittableJournalEntry[Offset, +A](offset: CommittableOffset[Offset],
                                               persistenceId: String,
                                               sequenceNr: Long,
                                               value: A)

trait AggregateJournal[Offset] {
  def committableEventSource[E: PersistentDecoder](
    entityName: String,
    consumerId: String
  ): Source[CommittableJournalEntry[Offset, E], NotUsed]
}
