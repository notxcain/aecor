package aecor.core.streaming

import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source

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
