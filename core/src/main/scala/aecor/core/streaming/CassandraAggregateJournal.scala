package aecor.core.streaming

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

  override def committableEventSource[E](
      aggregateName: String,
      consumerId: String): Source[CommittableJournalEntry[E], NotUsed] =
    extendedCassandraReadJournal
      .committableEventsByTag(aggregateName, consumerId)
      .map { x =>
        x.asInstanceOf[CommittableJournalEntry[E]]
      }
}
