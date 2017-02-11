package aecor.schedule

import java.time.{ Clock, LocalDate, LocalDateTime }
import java.util.UUID

import aecor.aggregate.{ AkkaRuntime, CorrelationId, Tagging }
import aecor.data.EventTag
import aecor.schedule.aggregate.{ DefaultScheduleAggregate, ScheduleAggregate, ScheduleEvent }
import aecor.streaming._
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.Timeout

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

object Schedule {
  def apply(system: ActorSystem,
            entityName: String,
            clock: Clock,
            dayZero: LocalDate,
            bucketLength: FiniteDuration,
            tickInterval: FiniteDuration,
            offsetStore: OffsetStore[UUID],
            repository: ScheduleEntryRepository)(implicit materializer: Materializer): Schedule =
    new ShardedSchedule(
      system,
      entityName,
      clock,
      dayZero,
      bucketLength,
      tickInterval,
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

  val runtime = AkkaRuntime(system)

  val eventTag = EventTag[ScheduleEvent](entityName)

  val scheduleAggregate: ScheduleAggregate[Future] = ScheduleAggregate.fromFunctionK(
    runtime.start(
      entityName,
      DefaultScheduleAggregate.asFunctionK,
      DefaultScheduleAggregate.correlation(bucketLength),
      Tagging(eventTag)
    )
  )

  private implicit val askTimeout: Timeout = Timeout(30.seconds)

  private val aggregateJournal = CassandraAggregateJournal(system)

  val consumerId = ConsumerId("ScheduleProcess")

  new ScheduleProcess(
    system,
    consumerId,
    dayZero,
    1.second,
    8,
    offsetStore,
    repository,
    scheduleAggregate, { consumerId =>
      aggregateJournal
        .committableEventsByTag(offsetStore, eventTag, consumerId)
        .map(_.map(_.event))
    },
    clock
  ).run()

  override def addScheduleEntry(scheduleName: String,
                                entryId: String,
                                correlationId: CorrelationId,
                                dueDate: LocalDateTime): Future[Unit] =
    scheduleAggregate.addScheduleEntry(scheduleName, entryId, correlationId, dueDate)

  override def committableScheduleEvents(
    scheduleName: String,
    consumerId: ConsumerId
  ): Source[Committable[JournalEntry[UUID, ScheduleEvent]], NotUsed] =
    aggregateJournal
      .committableEventsByTag[ScheduleEvent](
        offsetStore,
        EventTag(entityName),
        ConsumerId(scheduleName + consumerId.value)
      )
      .collect {
        case m if m.value.event.scheduleName == scheduleName => m
      }
}
