package aecor.core.schedule

import aecor.core.streaming.{CassandraReadJournalExtension, CommittableJournalEntry}
import akka.NotUsed
import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.stream.scaladsl.Source

import scala.concurrent.ExecutionContext

trait ScheduleJournal {
  def committableEvents(entityName: String, scheduleName: String, consumerId: String): Source[CommittableJournalEntry[ScheduleEvent], NotUsed]
}

object ScheduleJournal {
  def apply(system: ActorSystem, cassandraReadJournal: CassandraReadJournal)(implicit ec: ExecutionContext): ScheduleJournal =
    new CassandraScheduleJournal(system, cassandraReadJournal)
}

class CassandraScheduleJournal(system: ActorSystem, cassandraReadJournal: CassandraReadJournal)(implicit ec: ExecutionContext) extends ScheduleJournal {

  val extendedCassandraReadJournal = new CassandraReadJournalExtension(system, cassandraReadJournal)

  override def committableEvents(entityName: String, scheduleName: String, consumerId: String): Source[CommittableJournalEntry[ScheduleEvent], NotUsed] =
    extendedCassandraReadJournal.committableEventsByTag(entityName, consumerId).collect {
      case m@CommittableJournalEntry(offset, persistenceId, sequenceNr, e: ScheduleEvent)
        if e.scheduleName == scheduleName =>
          m.asInstanceOf[CommittableJournalEntry[ScheduleEvent]]
    }
}
