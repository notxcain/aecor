package aecor.schedule

import java.time.LocalDateTime
import java.util.UUID

import aecor.aggregate.Correlation._
import aecor.streaming.{ CassandraAggregateJournal, CommittableJournalEntry, OffsetStore }
import akka.actor.ActorSystem
import akka.cluster.sharding.{ ClusterSharding, ClusterShardingSettings }
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.Timeout
import akka.{ Done, NotUsed }

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
trait Schedule {
  def addScheduleEntry(scheduleName: String,
                       entryId: String,
                       correlationId: CorrelationId,
                       dueDate: LocalDateTime): Future[Done]
  def committableScheduleEvents(
    scheduleName: String,
    consumerId: String
  ): Source[CommittableJournalEntry[UUID, ScheduleEvent], NotUsed]
}

object Schedule {
  def apply(
    system: ActorSystem,
    entityName: String,
    bucketLength: FiniteDuration,
    tickInterval: FiniteDuration,
    offsetStore: OffsetStore[UUID]
  )(implicit executionContext: ExecutionContext): Schedule =
    new ShardedSchedule(system, entityName, bucketLength, tickInterval, offsetStore)
}

class ShardedSchedule(system: ActorSystem,
                      entityName: String,
                      bucketLength: FiniteDuration,
                      tickInterval: FiniteDuration,
                      offsetStore: OffsetStore[UUID])(implicit executionContext: ExecutionContext)
    extends Schedule {

  val scheduleRegion = ClusterSharding(system).start(
    typeName = entityName,
    entityProps = ScheduleActorSupervisor.props(entityName, tickInterval),
    settings = ClusterShardingSettings(system).withRememberEntities(true),
    extractEntityId = ScheduleActorSupervisor.extractEntityId(bucketLength),
    extractShardId = ScheduleActorSupervisor.extractShardId(10, bucketLength)
  )

  implicit val askTimeout: Timeout = Timeout(30.seconds)

  val aggregateJournal = CassandraAggregateJournal(system, offsetStore)

  override def addScheduleEntry(scheduleName: String,
                                entryId: String,
                                correlationId: CorrelationId,
                                dueDate: LocalDateTime): Future[Done] =
    (scheduleRegion ? AddScheduleEntry(scheduleName, entryId, correlationId, dueDate)).mapTo[Done]

  override def committableScheduleEvents(
    scheduleName: String,
    consumerId: String
  ): Source[CommittableJournalEntry[UUID, ScheduleEvent], NotUsed] =
    aggregateJournal
      .committableEventSource[ScheduleEvent](entityName, scheduleName + consumerId)
      .collect {
        case m if m.value.scheduleName == scheduleName => m
      }
}
