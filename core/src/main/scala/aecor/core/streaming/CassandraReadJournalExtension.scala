package aecor.core.streaming

import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{ EventEnvelope2, TimeBasedUUID }
import akka.stream.scaladsl.Source

import scala.concurrent.Future

trait Committable {
  def commit(): Future[Unit]
}

trait CommittableOffset[Offset] extends Committable {
  def value: Offset
  override def toString: String = s"CommittableOffset($value)"
}

object CommittableOffset {
  def apply[Offset](offset: Offset, committer: Offset => Future[Unit]): CommittableOffset[Offset] =
    new CommittableOffset[Offset] {
      override def value: Offset = offset
      override def commit(): Future[Unit] = committer(value)
    }
}

trait OffsetStore[Offset] {
  def getOffset(tag: String, consumerId: String): Future[Option[Offset]]
  def setOffset(tag: String, consumerId: String, offset: Offset): Future[Unit]
}

class CassandraReadJournalExtension(actorSystem: ActorSystem,
                                    offsetStore: OffsetStore[UUID],
                                    readJournal: CassandraReadJournal) {

  def committableEventsByTag[E](
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

object CassandraReadJournalExtension {
  def apply(actorSystem: ActorSystem,
            offsetStore: OffsetStore[UUID],
            readJournal: CassandraReadJournal): CassandraReadJournalExtension =
    new CassandraReadJournalExtension(actorSystem, offsetStore, readJournal)
}
