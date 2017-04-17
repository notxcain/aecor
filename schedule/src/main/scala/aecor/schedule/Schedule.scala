package aecor.schedule

import java.time._
import java.util.UUID

import aecor.data._
import aecor.effect.{ Async, Capture, CaptureFuture }
import aecor.runtime.akkapersistence._
import aecor.schedule.process.{
  DefaultScheduleEventJournal,
  PeriodicProcessRuntime,
  ScheduleProcess
}
import aecor.streaming.{ ConsumerId, OffsetStore }
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import cats.Monad
import cats.implicits._
import com.datastax.driver.core.utils.UUIDs

import scala.concurrent.duration._

trait Schedule[F[_]] {
  def addScheduleEntry(scheduleName: String,
                       entryId: String,
                       correlationId: CorrelationId,
                       dueDate: LocalDateTime): F[Unit]
  def committableScheduleEvents(
    scheduleName: String,
    consumerId: ConsumerId
  ): Source[Committable[F, JournalEntry[UUID, ScheduleEvent]], NotUsed]
}

object Schedule {
  def start[F[_]: Async: CaptureFuture: Capture: Monad](
    entityName: String,
    clock: Clock,
    dayZero: LocalDate,
    bucketLength: FiniteDuration,
    refreshInterval: FiniteDuration,
    eventualConsistencyDelay: FiniteDuration,
    repository: ScheduleEntryRepository[F],
    aggregateJournal: AggregateJournal[UUID, ScheduleEvent],
    offsetStore: OffsetStore[F, UUID],
    consumerId: ConsumerId = ConsumerId("io.aecor.schedule.ScheduleProcess")
  )(implicit system: ActorSystem, materializer: Materializer): F[Schedule[F]] = {

    val eventTag = EventTag[ScheduleEvent](entityName)

    val runtime = AkkaPersistenceRuntime(system)

    def uuidToLocalDateTime(store: OffsetStore[F, UUID],
                            zoneId: ZoneId): OffsetStore[F, LocalDateTime] =
      store.imap(
        uuid => LocalDateTime.ofInstant(Instant.ofEpochMilli(UUIDs.unixTimestamp(uuid)), zoneId),
        value => UUIDs.startOf(value.atZone(zoneId).toInstant.toEpochMilli)
      )

    def startAggregate =
      for {
        f <- runtime.start(
              entityName,
              DefaultScheduleAggregate(Capture[F].capture(ZonedDateTime.now(clock))).asFunctionK,
              DefaultScheduleAggregate.correlation,
              Tagging.const(eventTag)
            )
      } yield ScheduleAggregate.fromFunctionK(f)

    def startProcess(aggregate: ScheduleAggregate[F]) = {
      val journal =
        DefaultScheduleEventJournal[F](consumerId, 8, offsetStore, aggregateJournal, eventTag)

      val process = ScheduleProcess(
        journal,
        dayZero,
        consumerId,
        uuidToLocalDateTime(offsetStore, clock.getZone),
        eventualConsistencyDelay,
        repository,
        aggregate,
        Capture[F].capture(LocalDateTime.now(clock)),
        8
      )
      PeriodicProcessRuntime(entityName, refreshInterval, process).run(system)
    }

    def createSchedule(aggregate: ScheduleAggregate[F]): Schedule[F] =
      new DefaultSchedule(clock, aggregate, bucketLength, aggregateJournal, offsetStore, eventTag)

    for {
      aggregate <- startAggregate
      _ <- startProcess(aggregate)
      schedule = createSchedule(aggregate)
    } yield schedule
  }

}
