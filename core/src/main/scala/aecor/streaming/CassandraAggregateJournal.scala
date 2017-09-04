package aecor.streaming

import java.util.UUID

import aecor.aggregate.serialization.{ PersistentDecoder, PersistentRepr }
import aecor.data.EventTag
import akka.NotUsed
import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{ EventEnvelope, NoOffset, PersistenceQuery, TimeBasedUUID }
import akka.stream.scaladsl.Source

import scala.concurrent.{ ExecutionContext, Future }

class CassandraAggregateJournal(system: ActorSystem, journalIdentifier: String, parallelism: Int)(
  implicit executionContext: ExecutionContext
) extends AggregateJournal[UUID] {

  private val readJournal: CassandraReadJournal =
    PersistenceQuery(system).readJournalFor[CassandraReadJournal](journalIdentifier)

  private def createSource[E: PersistentDecoder](
    inner: Source[EventEnvelope, NotUsed]
  ): Source[JournalEntry[UUID, E], NotUsed] =
    inner.mapAsync(parallelism) {
      case EventEnvelope(eventOffset, persistenceId, sequenceNr, event) =>
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
                    s"Unexpected persistent representation $other at sequenceNr = [$sequenceNr], persistenceId = [$persistenceId]"
                  )
                )
            }
          case other =>
            Future.failed(
              new RuntimeException(
                s"Unexpected offset of type ${other.getClass} at sequenceNr = [$sequenceNr], persistenceId = [$persistenceId]"
              )
            )
        }
    }

  def eventsByTag[E: PersistentDecoder](
    tag: EventTag[E],
    offset: Option[UUID]
  ): Source[JournalEntry[UUID, E], NotUsed] =
    createSource(
      readJournal
        .eventsByTag(tag.value, offset.map(TimeBasedUUID).getOrElse(NoOffset))
    )

  override def currentEventsByTag[E: PersistentDecoder](
    tag: EventTag[E],
    offset: Option[UUID]
  ): Source[JournalEntry[UUID, E], NotUsed] =
    createSource(
      readJournal
        .currentEventsByTag(tag.value, offset.map(TimeBasedUUID).getOrElse(NoOffset))
    )

}

object CassandraAggregateJournal {
  def apply(
    system: ActorSystem,
    journalIdentifier: String = CassandraReadJournal.Identifier,
    parallelism: Int = 8
  )(implicit executionContext: ExecutionContext): AggregateJournal[UUID] =
    new CassandraAggregateJournal(system, journalIdentifier, parallelism)
}
