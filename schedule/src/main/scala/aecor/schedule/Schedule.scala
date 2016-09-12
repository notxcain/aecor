package aecor.schedule

import java.time.LocalDateTime

import aecor.core.message.Correlation._
import aecor.core.streaming.{CassandraReadJournalExtension, CommittableJournalEntry, JournalEntry, OffsetStore}
import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.pattern.ask
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.scaladsl.Source
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration._
trait Schedule {
  def addScheduleEntry(scheduleName: String, entryId: String, correlationId: CorrelationId, dueDate: LocalDateTime): Future[Done]
  def committableScheduleEvents(scheduleName: String, consumerId: String): Source[CommittableJournalEntry[ScheduleEvent], NotUsed]
}

object Schedule {
  def apply(system: ActorSystem, entityName: String, bucketLength: FiniteDuration, tickInterval: FiniteDuration, offsetStore: OffsetStore): Schedule =
    new ShardedSchedule(system, entityName, bucketLength, tickInterval, offsetStore)
}

class ShardedSchedule(system: ActorSystem, entityName: String, bucketLength: FiniteDuration, tickInterval: FiniteDuration, offsetStore: OffsetStore) extends Schedule {
  val scheduleRegion = ClusterSharding(system).start(
    typeName = entityName,
    entityProps = ScheduleActorSupervisor.props(entityName, tickInterval),
    settings = ClusterShardingSettings(system).withRememberEntities(true),
    extractEntityId = ScheduleActorSupervisor.extractEntityId(bucketLength),
    extractShardId = ScheduleActorSupervisor.extractShardId(10, bucketLength)
  )

  implicit val askTimeout: Timeout = Timeout(30.seconds)

  val cassandraReadJournal = PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
  val extendedCassandraReadJournal = new CassandraReadJournalExtension(system, offsetStore, cassandraReadJournal)

  override def addScheduleEntry(scheduleName: String, entryId: String, correlationId: CorrelationId, dueDate: LocalDateTime): Future[Done] =
    (scheduleRegion ? AddScheduleEntry(scheduleName, entryId, correlationId, dueDate)).mapTo[Done]

  override def committableScheduleEvents(scheduleName: String, consumerId: String): Source[CommittableJournalEntry[ScheduleEvent], NotUsed] =
    extendedCassandraReadJournal.committableEventsByTag(entityName, scheduleName + consumerId).collect {
      case m@(offset, JournalEntry(persistenceId, sequenceNr, e: ScheduleEvent))
        if e.scheduleName == scheduleName =>
        m.asInstanceOf[CommittableJournalEntry[ScheduleEvent]]
    }
}