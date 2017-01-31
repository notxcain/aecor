package aecor.streaming

import java.util.UUID

import aecor.aggregate.serialization.{ PersistentDecoder, PersistentRepr }
import aecor.data.EventTag
import akka.NotUsed
import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{ EventEnvelope2, PersistenceQuery, TimeBasedUUID }
import akka.stream.scaladsl.Source

import scala.concurrent.{ ExecutionContext, Future }

class CassandraAggregateJournal(system: ActorSystem, journalIdentifier: String)(
  implicit executionContext: ExecutionContext
) extends AggregateJournal[UUID] {

  private val readJournal: CassandraReadJournal =
    PersistenceQuery(system).readJournalFor[CassandraReadJournal](journalIdentifier)

  def eventsByTag[E: PersistentDecoder](
    tag: EventTag[E],
    offset: Option[UUID]
  ): Source[JournalEntry[UUID, E], NotUsed] =
    readJournal
      .eventsByTag(tag.value, TimeBasedUUID(offset.getOrElse(readJournal.firstOffset)))
      .mapAsync(8) {
        case EventEnvelope2(eventOffset, persistenceId, sequenceNr, event) =>
          Future(eventOffset).flatMap {
            case TimeBasedUUID(offsetValue) =>
              event match {
                case repr: PersistentRepr =>
                  PersistentDecoder[E]
                    .decode(repr)
                    .right
                    .map { event =>
                      JournalEntry(offsetValue, persistenceId, sequenceNr, event)
                    }
                    .fold(Future.failed, Future.successful)
                case other =>
                  Future.failed(
                    new RuntimeException(
                      s"Unexpected persistent representation $other at sequenceNr = [$sequenceNr], persistenceId = [$persistenceId], tag = [$tag]"
                    )
                  )
              }
            case other =>
              Future.failed(
                new RuntimeException(
                  s"Unexpected offset of type ${other.getClass} at sequenceNr = [$sequenceNr], persistenceId = [$persistenceId], tag = [$tag]"
                )
              )
          }
      }

}

object CassandraAggregateJournal {
  def apply(system: ActorSystem, journalIdentifier: String = CassandraReadJournal.Identifier)(
    implicit executionContext: ExecutionContext
  ): AggregateJournal[UUID] =
    new CassandraAggregateJournal(system, journalIdentifier)
}
