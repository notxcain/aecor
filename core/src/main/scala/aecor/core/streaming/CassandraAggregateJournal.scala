package aecor.core.streaming

import aecor.core.aggregate.{AggregateEvent, AggregateName, EventContract}
import akka.NotUsed
import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.scaladsl.Source

import scala.reflect.ClassTag

class CassandraAggregateJournal(system: ActorSystem, offsetStore: OffsetStore) extends AggregateJournal {

  val cassandraReadJournal = PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
  val extendedCassandraReadJournal = new CassandraReadJournalExtension(system, offsetStore, cassandraReadJournal)

  def committableEventSourceFor[A] = new MkCommittableEventSource[A] {
    override def apply[E](consumerId: String)
      (implicit name: AggregateName[A],
        contract: EventContract.Aux[A, E],
        E: ClassTag[E]
      ): Source[CommittableJournalEntry[AggregateEvent[E]], NotUsed] =
      extendedCassandraReadJournal.committableEventsByTag(name.value, consumerId).collect {
        case m@(offset, JournalEntry(persistenceId, sequenceNr, AggregateEvent(id, event: E, timestamp))) =>
          m.asInstanceOf[CommittableJournalEntry[AggregateEvent[E]]]
      }
  }
}