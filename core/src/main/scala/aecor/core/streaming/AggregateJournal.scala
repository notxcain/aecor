package aecor.core.streaming

import aecor.core.aggregate._
import akka.NotUsed
import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.stream.scaladsl.Source

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

trait AggregateJournal {
  trait MkCommittableEventSource[A] {
    def apply[E](consumerId: String)
      (implicit name: AggregateName[A],
        contract: EventContract.Aux[A, E],
        E: ClassTag[E]
      ): Source[CommittableJournalEntry[AggregateEventEnvelope[E]], NotUsed]
  }

  def committableEventSourceFor[A]: MkCommittableEventSource[A]
}

object AggregateJournal {
  def apply(actorSystem: ActorSystem, cassandraReadJournal: CassandraReadJournal)(implicit ec: ExecutionContext): AggregateJournal = new CassandraAggregateJournal(actorSystem, cassandraReadJournal)
}
