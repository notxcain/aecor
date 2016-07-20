package aecor.core.streaming

import java.time.Instant

import aecor.core.entity.{EntityName, EventContract, PersistentEntityEventEnvelope}
import aecor.core.message.MessageId
import akka.NotUsed
import akka.persistence.cassandra.query.UUIDEventEnvelope
import akka.stream.scaladsl.Source

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag


case class JournalEntry[A](persistenceId: String, sequenceNr: Long, eventId: MessageId, event: A, timestamp: Instant, causedBy: MessageId)

class Replicator(extendedCassandraReadJournal: ExtendedCassandraReadJournal) {

  sealed trait MkCommittable[A] {
    def apply[E](consumerId: String)(implicit name: EntityName[A], contract: EventContract.Aux[A, E], E: ClassTag[E], ec: ExecutionContext): Source[CommittableMessage[JournalEntry[E]], NotUsed]
  }

  def committableEventSourceFor[A] = new MkCommittable[A] {
    override def apply[E](consumerId: String)(implicit name: EntityName[A], contract: EventContract.Aux[A, E], E: ClassTag[E], ec: ExecutionContext): Source[CommittableMessage[JournalEntry[E]], NotUsed] =
      extendedCassandraReadJournal.committableEventsByTag(name.value, consumerId).collect {
        case CommittableMessage(committable, UUIDEventEnvelope(_, persistenceId, sequenceNr, PersistentEntityEventEnvelope(id, event: E, timestamp, causedBy))) =>
          CommittableMessage(committable, JournalEntry(persistenceId, sequenceNr, id, event, timestamp, causedBy))
      }
  }
}

object Replicator {
  def apply(extendedCassandraReadJournal: ExtendedCassandraReadJournal): Replicator = new Replicator(extendedCassandraReadJournal)
}
