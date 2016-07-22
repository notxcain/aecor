package aecor.core.streaming

import java.time.Instant

import aecor.core.aggregate._
import akka.NotUsed
import akka.stream.scaladsl.Source

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

class Journal(extendedCassandraReadJournal: ExtendedCassandraReadJournal) {

  sealed trait MkCommittableEventSource[A] {
    def apply[E](consumerId: String)
      (implicit name: AggregateName[A],
        contract: EventContract.Aux[A, E],
        E: ClassTag[E],
        ec: ExecutionContext
      ): Source[CommittableJournalEntry[AggregateEventEnvelope[E]], NotUsed]
  }

  def committableEventSourceFor[A] = new MkCommittableEventSource[A] {
    override def apply[E](consumerId: String)
      (implicit name: AggregateName[A],
        contract: EventContract.Aux[A, E],
        E: ClassTag[E],
        ec: ExecutionContext
      ): Source[CommittableJournalEntry[AggregateEventEnvelope[E]], NotUsed] =
      extendedCassandraReadJournal.committableEventsByTag(name.value, consumerId).collect {
        case m@CommittableJournalEntry(offset, persistenceId, sequenceNr, AggregateEventEnvelope(id, event: E, timestamp, causedBy)) =>
          m.asInstanceOf[CommittableJournalEntry[AggregateEventEnvelope[E]]]
      }
  }
}

object Journal {
  def apply(extendedCassandraReadJournal: ExtendedCassandraReadJournal): Journal = new Journal(extendedCassandraReadJournal)
}
