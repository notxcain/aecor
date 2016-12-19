package aecor.streaming

import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source

case class CommittableJournalEntry[Offset, +A](offset: CommittableOffset[Offset],
                                               persistenceId: String,
                                               sequenceNr: Long,
                                               value: A)

trait AggregateJournal[Offset] {
  def committableEventSource[E](
    entityName: String,
    consumerId: String
  ): Source[CommittableJournalEntry[Offset, E], NotUsed]
}

object AggregateJournal {
  def apply(actorSystem: ActorSystem, offsetStore: OffsetStore[UUID]): AggregateJournal[UUID] =
    new CassandraAggregateJournal(actorSystem, offsetStore)
}
