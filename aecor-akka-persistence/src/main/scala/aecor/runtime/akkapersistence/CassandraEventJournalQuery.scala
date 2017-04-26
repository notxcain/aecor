package aecor.runtime.akkapersistence

import java.util.UUID

import aecor.data.EventTag
import aecor.runtime.akkapersistence.serialization.{ PersistentDecoder, PersistentRepr }
import akka.NotUsed
import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{ EventEnvelope2, NoOffset, PersistenceQuery, TimeBasedUUID }
import akka.stream.scaladsl.Source

import scala.concurrent.{ ExecutionContext, Future }

class CassandraEventJournalQuery[E: PersistentDecoder](
  system: ActorSystem,
  journalIdentifier: String,
  parallelism: Int
)(implicit executionContext: ExecutionContext)
    extends EventJournalQuery[UUID, E] {

  private val readJournal: CassandraReadJournal =
    PersistenceQuery(system).readJournalFor[CassandraReadJournal](journalIdentifier)

  private def createSource(
    inner: Source[EventEnvelope2, NotUsed]
  ): Source[JournalEntry[UUID, E], NotUsed] =
    inner.mapAsync(parallelism) {
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

  def eventsByTag(tag: EventTag[E], offset: Option[UUID]): Source[JournalEntry[UUID, E], NotUsed] =
    createSource(
      readJournal
        .eventsByTag(tag.value, offset.map(TimeBasedUUID).getOrElse(NoOffset))
    )

  override def currentEventsByTag(tag: EventTag[E],
                                  offset: Option[UUID]): Source[JournalEntry[UUID, E], NotUsed] =
    createSource(
      readJournal
        .currentEventsByTag(tag.value, offset.map(TimeBasedUUID).getOrElse(NoOffset))
    )

}

object CassandraEventJournalQuery {
  def apply[E: PersistentDecoder](
    system: ActorSystem,
    journalIdentifier: String = CassandraReadJournal.Identifier,
    parallelism: Int = 8
  )(implicit executionContext: ExecutionContext): EventJournalQuery[UUID, E] =
    new CassandraEventJournalQuery(system, journalIdentifier, parallelism)
}
