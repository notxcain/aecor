package aecor.core.streaming

import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{ EventEnvelope2, PersistenceQuery, TimeBasedUUID }
import akka.stream.scaladsl.Source

class CassandraAggregateJournal(system: ActorSystem, offsetStore: OffsetStore[UUID])
    extends AggregateJournal[UUID] {

  private val readJournal: CassandraReadJournal =
    PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

  override def committableEventSource[E](
    tag: String,
    consumerId: String
  ): Source[CommittableJournalEntry[UUID, E], NotUsed] =
    Source
      .single(NotUsed)
      .mapAsync(1) { _ =>
        offsetStore.getOffset(tag, consumerId)
      }
      .flatMapConcat { storedOffset =>
        readJournal
          .eventsByTag(tag, TimeBasedUUID(storedOffset.getOrElse(readJournal.firstOffset)))
          .map {
            case EventEnvelope2(offset, persistenceId, sequenceNr, event) =>
              CommittableJournalEntry(
                CommittableOffset(
                  offset.asInstanceOf[TimeBasedUUID].value,
                  offsetStore.setOffset(tag, consumerId, _)
                ),
                persistenceId,
                sequenceNr,
                event.asInstanceOf[E]
              )
          }
      }

}
