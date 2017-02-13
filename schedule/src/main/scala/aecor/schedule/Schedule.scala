package aecor.schedule

import java.time.{ Clock, LocalDate, LocalDateTime }
import java.util.UUID

import aecor.aggregate.{ AkkaRuntime, CorrelationId, Tagging }
import aecor.data.EventTag
import aecor.streaming._
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import scala.concurrent.Future
import scala.concurrent.duration._

trait Schedule {
  def addScheduleEntry(scheduleName: String,
                       entryId: String,
                       correlationId: CorrelationId,
                       dueDate: LocalDateTime): Future[Unit]
  def committableScheduleEvents(
    scheduleName: String,
    consumerId: ConsumerId
  ): Source[Committable[JournalEntry[UUID, ScheduleEvent]], NotUsed]
}

final case class ScheduleSettings(entityName: String,
                                  dayZero: LocalDate,
                                  refreshInterval: FiniteDuration)

object Schedule {
  def apply(system: ActorSystem,
            entityName: String,
            clock: Clock,
            dayZero: LocalDate,
            bucketLength: FiniteDuration,
            refreshInterval: FiniteDuration,
            offsetStore: OffsetStore[UUID],
            repository: ScheduleEntryRepository)(implicit materializer: Materializer): Schedule =
    new ShardedSchedule(
      system,
      entityName,
      clock,
      dayZero,
      bucketLength,
      refreshInterval,
      offsetStore,
      repository
    )
}

class ShardedSchedule(system: ActorSystem,
                      entityName: String,
                      clock: Clock,
                      dayZero: LocalDate,
                      bucketLength: FiniteDuration,
                      tickInterval: FiniteDuration,
                      offsetStore: OffsetStore[UUID],
                      repository: ScheduleEntryRepository)(implicit materializer: Materializer)
    extends Schedule {

  import materializer.executionContext

  private val runtime = AkkaRuntime(system)

  private val aggregateJournal = CassandraAggregateJournal(system)

  private val eventTag = EventTag[ScheduleEvent](entityName)

  private val scheduleAggregate = ScheduleAggregate.fromFunctionK(
    runtime.start(
      entityName,
      DefaultScheduleAggregate(clock).asFunctionK,
      DefaultScheduleAggregate.correlation,
      Tagging(eventTag)
    )
  )

  private val consumerId = ConsumerId("io.aecor.schedule.ScheduleProcess")

  private val eventSource = { consumerId: ConsumerId =>
    aggregateJournal
      .committableEventsByTag(offsetStore, eventTag, consumerId)
      .map(_.map(_.event))
  }

  new ScheduleProcess(
    system,
    entityName,
    consumerId,
    dayZero,
    1.second,
    8,
    offsetStore,
    repository,
    scheduleAggregate,
    eventSource,
    clock
  ).run()

  override def addScheduleEntry(scheduleName: String,
                                entryId: String,
                                correlationId: CorrelationId,
                                dueDate: LocalDateTime): Future[Unit] = {
    val timeBucket =
      dueDate.atZone(clock.getZone).toEpochSecond / bucketLength.toSeconds
    scheduleAggregate.addScheduleEntry(
      scheduleName,
      timeBucket.toString,
      entryId,
      correlationId,
      dueDate
    )
  }

  override def committableScheduleEvents(
    scheduleName: String,
    consumerId: ConsumerId
  ): Source[Committable[JournalEntry[UUID, ScheduleEvent]], NotUsed] =
    aggregateJournal
      .committableEventsByTag[ScheduleEvent](
        offsetStore,
        eventTag,
        ConsumerId(scheduleName + consumerId.value)
      )
      .collect {
        case m if m.value.event.scheduleName == scheduleName => m
      }
}
