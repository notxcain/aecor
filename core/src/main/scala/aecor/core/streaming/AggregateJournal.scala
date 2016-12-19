package aecor.core.streaming

import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source

import scala.concurrent.ExecutionContext

trait AggregateJournal[Offset] {
  def committableEventSource[E](
      entityName: String,
      consumerId: String): Source[CommittableJournalEntry[Offset, E], NotUsed]
}

object AggregateJournal {
  def apply(actorSystem: ActorSystem, offsetStore: OffsetStore[UUID])(
      implicit executionContext: ExecutionContext): AggregateJournal[UUID] =
    new CassandraAggregateJournal(actorSystem, offsetStore)
}
