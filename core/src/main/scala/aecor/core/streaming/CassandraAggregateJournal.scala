package aecor.core.streaming

import aecor.core.aggregate.AggregateName
import akka.NotUsed
import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.scaladsl.Source

import scala.concurrent.ExecutionContext

class CassandraAggregateJournal(system: ActorSystem, offsetStore: OffsetStore)(
    implicit executionContext: ExecutionContext)
    extends AggregateJournal {

  val cassandraReadJournal: CassandraReadJournal = PersistenceQuery(system)
    .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
  val extendedCassandraReadJournal: CassandraReadJournalExtension =
    new CassandraReadJournalExtension(system,
                                      offsetStore,
                                      cassandraReadJournal)

  def committableEventSourceFor[A] = new MkCommittableEventSource[A] {
    override def apply[E](consumerId: String)(
        implicit name: AggregateName[A],
        contract: EventContract.Aux[A, E])
      : Source[CommittableJournalEntry[E], NotUsed] =
      extendedCassandraReadJournal
        .committableEventsByTag(name.value, consumerId)
        .map { x =>
          x.asInstanceOf[CommittableJournalEntry[E]]
        }
  }
}
