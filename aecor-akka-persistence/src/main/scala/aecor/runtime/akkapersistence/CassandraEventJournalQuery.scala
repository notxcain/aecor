package aecor.runtime.akkapersistence

import java.util.UUID

import aecor.data.EventTag
import aecor.runtime.akkapersistence.serialization.{ PersistentDecoder, PersistentRepr }
import akka.NotUsed
import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query._
import akka.stream.scaladsl.Source

import scala.concurrent.Future

class CassandraEventJournalQuery[E: PersistentDecoder](system: ActorSystem, parallelism: Int)
    extends EventJournalQuery[UUID, E] {

  private val decoder = PersistentDecoder[E]

  private val readJournal =
    PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

  private def createSource(
    inner: Source[EventEnvelope, NotUsed]
  ): Source[JournalEntry[UUID, E], NotUsed] =
    inner.mapAsync(parallelism) {
      case EventEnvelope(offset, persistenceId, sequenceNr, event) =>
        offset match {
          case TimeBasedUUID(offsetValue) =>
            event match {
              case repr: PersistentRepr =>
                decoder
                  .decode(repr)
                  .right
                  .map { event =>
                    JournalEntry(offsetValue, persistenceId, sequenceNr, event)
                  }
                  .fold(Future.failed, Future.successful)
              case other =>
                Future.failed(
                  new IllegalArgumentException(
                    s"Unexpected persistent representation [$other] at sequenceNr = [$sequenceNr], persistenceId = [$persistenceId]"
                  )
                )
            }
          case other =>
            Future.failed(
              new IllegalArgumentException(
                s"Unexpected offset of type [${other.getClass}] at sequenceNr = [$sequenceNr], persistenceId = [$persistenceId]"
              )
            )
        }
    }

  def eventsByTag(tag: EventTag, offset: Option[UUID]): Source[JournalEntry[UUID, E], NotUsed] =
    createSource(
      readJournal
        .eventsByTag(tag.value, offset.map(TimeBasedUUID).getOrElse(NoOffset))
    )

  override def currentEventsByTag(tag: EventTag,
                                  offset: Option[UUID]): Source[JournalEntry[UUID, E], NotUsed] =
    createSource(
      readJournal
        .currentEventsByTag(tag.value, offset.map(TimeBasedUUID).getOrElse(NoOffset))
    )

}

object CassandraEventJournalQuery {
  def apply[E: PersistentDecoder](system: ActorSystem,
                                  decodingParallelism: Int = 8): EventJournalQuery[UUID, E] =
    new CassandraEventJournalQuery(system, decodingParallelism)
}
