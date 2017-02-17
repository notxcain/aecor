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
import cats.data.Reader

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

private[schedule] class ConfiguredSchedule(
  system: ActorSystem,
  entityName: String,
  clock: Clock,
  dayZero: LocalDate,
  bucketLength: FiniteDuration,
  tickInterval: FiniteDuration,
  eventualConsistencyDelay: FiniteDuration,
  repository: ScheduleEntryRepository,
  aggregateJournal: AggregateJournal[UUID],
  offsetStore: OffsetStore[UUID],
  consumerId: ConsumerId
)(implicit materializer: Materializer) {

  private val runtime = AkkaRuntime(system)

  private val eventTag = EventTag[ScheduleEvent](entityName)

  private val startAggregate = Reader { _: Unit =>
    ScheduleAggregate.fromFunctionK(
      runtime.start(
        entityName,
        DefaultScheduleAggregate(clock).asFunctionK,
        DefaultScheduleAggregate.correlation,
        Tagging(eventTag)
      )
    )
  }

  private def startProcess(aggregate: ScheduleAggregate[Future]) =
    ScheduleProcess(
      clock = clock,
      entityName = entityName,
      consumerId = consumerId,
      dayZero = dayZero,
      refreshInterval = 1.second,
      eventualConsistencyDelay = eventualConsistencyDelay,
      parallelism = 8,
      offsetStore = offsetStore,
      repository = repository,
      scheduleAggregate = aggregate,
      aggregateJournal = aggregateJournal,
      eventTag = eventTag
    ).run(system)

  private def createSchedule(aggregate: ScheduleAggregate[Future]): Schedule =
    new DefaultSchedule(clock, aggregate, bucketLength, aggregateJournal, offsetStore, eventTag)

  def start: Reader[Unit, Schedule] =
    for {
      aggregate <- startAggregate
      _ <- startProcess(aggregate)
      schedule = createSchedule(aggregate)
    } yield schedule
}

object Schedule {
  def start(
    entityName: String,
    clock: Clock,
    dayZero: LocalDate,
    bucketLength: FiniteDuration,
    refreshInterval: FiniteDuration,
    eventualConsistencyDelay: FiniteDuration,
    repository: ScheduleEntryRepository,
    aggregateJournal: AggregateJournal[UUID],
    offsetStore: OffsetStore[UUID],
    consumerId: ConsumerId = ConsumerId("io.aecor.schedule.ScheduleProcess")
  )(implicit system: ActorSystem, materializer: Materializer): Reader[Unit, Schedule] =
    new ConfiguredSchedule(
      system,
      entityName,
      clock,
      dayZero,
      bucketLength,
      refreshInterval,
      eventualConsistencyDelay,
      repository,
      aggregateJournal,
      offsetStore,
      consumerId
    ).start
}
