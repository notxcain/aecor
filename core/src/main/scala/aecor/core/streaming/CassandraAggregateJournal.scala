package aecor.core.streaming

import aecor.core.aggregate.{AggregateEvent, AggregateName, EventContract}
import akka.NotUsed
import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.stream.scaladsl.Source

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

class CassandraAggregateJournal(actorSystem: ActorSystem, cassandraReadJournal: CassandraReadJournal)(implicit ec: ExecutionContext) extends AggregateJournal {

  val extendedCassandraReadJournal = new CassandraReadJournalExtension(actorSystem, cassandraReadJournal)

  def committableEventSourceFor[A] = new MkCommittableEventSource[A] {
    override def apply[E](consumerId: String)
      (implicit name: AggregateName[A],
        contract: EventContract.Aux[A, E],
        E: ClassTag[E]
      ): Source[CommittableJournalEntry[AggregateEvent[E]], NotUsed] =
      extendedCassandraReadJournal.committableEventsByTag(name.value, consumerId).collect {
        case m@CommittableJournalEntry(offset, persistenceId, sequenceNr, AggregateEvent(id, event: E, timestamp, causedBy)) =>
          m.asInstanceOf[CommittableJournalEntry[AggregateEvent[E]]]
      }
  }
}