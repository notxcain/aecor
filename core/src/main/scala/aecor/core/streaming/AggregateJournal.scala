package aecor.core.streaming

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source

import scala.concurrent.ExecutionContext

trait AggregateJournal {

  def committableEventSource[E](
      aggregateName: String,
      consumerId: String): Source[CommittableJournalEntry[E], NotUsed]
}

object AggregateJournal {
  def apply(actorSystem: ActorSystem, offsetStore: OffsetStore)(
      implicit executionContext: ExecutionContext): AggregateJournal =
    new CassandraAggregateJournal(actorSystem, offsetStore)
}
