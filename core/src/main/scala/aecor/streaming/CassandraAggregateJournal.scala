package aecor.streaming

import java.util.UUID

import aecor.serialization.PersistentDecoder
import aecor.serialization.akka.PersistentRepr
import akka.NotUsed
import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{ EventEnvelope2, PersistenceQuery, TimeBasedUUID }
import akka.stream.scaladsl.Source

import scala.concurrent.Future
import scala.util.{ Failure, Success }

class CassandraAggregateJournal(system: ActorSystem, offsetStore: OffsetStore[UUID])
    extends AggregateJournal[UUID] {

  private val readJournal: CassandraReadJournal =
    PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

  override def committableEventSource[E: PersistentDecoder](
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
              offset match {
                case TimeBasedUUID(offsetValue) =>
                  event match {
                    case repr: PersistentRepr =>
                      PersistentDecoder[E]
                        .decode(repr)
                        .right
                        .map { event =>
                          CommittableJournalEntry(CommittableOffset(offsetValue, { x: UUID =>
                            offsetStore.setOffset(tag, consumerId, x)
                          }), persistenceId, sequenceNr, event)
                        }
                        .fold(Failure(_), Success(_))
                    case other =>
                      Failure(
                        new RuntimeException(
                          s"Unexpected persistent representation $other at sequenceNr = [$sequenceNr], persistenceId = [$persistenceId], tag = [$tag], consumerId = [$consumerId]"
                        )
                      )
                  }
                case other =>
                  Failure(
                    new RuntimeException(
                      s"Unexpected offset $other at sequenceNr = [$sequenceNr], persistenceId = [$persistenceId], tag = [$tag], consumerId = [$consumerId]"
                    )
                  )
              }
          }
          .mapAsync(8)(Future.fromTry)
      }

}

object CassandraAggregateJournal {
  def apply(system: ActorSystem, offsetStore: OffsetStore[UUID]): AggregateJournal[UUID] =
    new CassandraAggregateJournal(system, offsetStore)
}
