package aecor.core.streaming

import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.scaladsl.Source

import scala.concurrent.ExecutionContext

class CassandraAggregateJournal(system: ActorSystem,
                                offsetStore: OffsetStore[UUID])(
    implicit executionContext: ExecutionContext)
    extends AggregateJournal[UUID] {

  val cassandraReadJournal: CassandraReadJournal = PersistenceQuery(system)
    .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
  val extendedCassandraReadJournal: CassandraReadJournalExtension =
    new CassandraReadJournalExtension(system,
                                      offsetStore,
                                      cassandraReadJournal)

  override def committableEventSource[E](
      entityName: String,
      consumerId: String): Source[CommittableJournalEntry[UUID, E], NotUsed] =
    extendedCassandraReadJournal
      .committableEventsByTag[E](entityName, consumerId)

}
