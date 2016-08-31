package aecor.schedule

import java.time.LocalDateTime

import aecor.core.message.Correlation._
import akka.Done
import akka.actor.ActorSystem
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration._
trait Schedule {
  def addScheduleEntry(scheduleName: String, entryId: String, correlationId: CorrelationId, dueDate: LocalDateTime): Future[Done]
}

object Schedule {
  def apply(system: ActorSystem, entityName: String, bucketLength: FiniteDuration): Schedule =
    new ShardedSchedule(system, entityName, bucketLength)
}

class ShardedSchedule(system: ActorSystem, entityName: String, bucketLength: FiniteDuration) extends Schedule {
  val scheduleRegion = ClusterSharding(system).start(
    typeName = entityName,
    entityProps = ScheduleActorSupervisor.props(entityName),
    settings = ClusterShardingSettings(system).withRememberEntities(true),
    extractEntityId = ScheduleActorSupervisor.extractEntityId(bucketLength),
    extractShardId = ScheduleActorSupervisor.extractShardId(10, bucketLength)
  )

  implicit val askTimeout: Timeout = Timeout(30.seconds)

  override def addScheduleEntry(scheduleName: String, entryId: String, correlationId: CorrelationId, dueDate: LocalDateTime): Future[Done] =
    (scheduleRegion ? AddScheduleEntry(scheduleName, entryId, correlationId, dueDate)).mapTo[Done]
}