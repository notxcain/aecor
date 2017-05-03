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
import aecor.util.KeyValueStore
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
  final case class ScheduleSettings(bucketLength: FiniteDuration,
                                    refreshInterval: FiniteDuration,
                                    eventualConsistencyDelay: FiniteDuration,
                                    consumerId: ConsumerId)

  def start[F[_]: Async: CaptureFuture: Capture: Monad](
    entityName: String,
    dayZero: LocalDate,
    clock: Clock,
    repository: ScheduleEntryRepository[F],
    aggregateJournal: EventJournalQuery[UUID, ScheduleEvent],
    offsetStore: KeyValueStore[F, TagConsumerId, UUID],
    settings: ScheduleSettings = ScheduleSettings(
      1.day,
      10.seconds,
      40.seconds,
      ConsumerId("io.aecor.schedule.ScheduleProcess")
    )
  )(implicit system: ActorSystem, materializer: Materializer): F[Schedule[F]] = {

    val eventTag = EventTag[ScheduleEvent](entityName)

    val runtime = AkkaPersistenceRuntime(system)

    def uuidToLocalDateTime(zoneId: ZoneId): KeyValueStore[F, TagConsumerId, LocalDateTime] =
      offsetStore.imap(
        uuid => LocalDateTime.ofInstant(Instant.ofEpochMilli(UUIDs.unixTimestamp(uuid)), zoneId),
        value => UUIDs.startOf(value.atZone(zoneId).toInstant.toEpochMilli)
      )

    def startAggregate =
      for {
        f <- runtime.start(
              entityName,
              DefaultScheduleAggregate.correlation,
              DefaultScheduleAggregate.behavior(Capture[F].capture(ZonedDateTime.now(clock))),
              Tagging.const(eventTag)
            )
      } yield ScheduleAggregate.fromFunctionK(f)

    def startProcess(aggregate: ScheduleAggregate[F]) = {
      val journal =
        DefaultScheduleEventJournal[F](
          settings.consumerId,
          8,
          offsetStore,
          aggregateJournal,
          eventTag
        )

      val process = ScheduleProcess(
        journal,
        dayZero,
        settings.consumerId,
        uuidToLocalDateTime(clock.getZone),
        settings.eventualConsistencyDelay,
        repository,
        aggregate,
        Capture[F].capture(LocalDateTime.now(clock)),
        8
      )
      PeriodicProcessRuntime(entityName, settings.refreshInterval, process).run(system)
    }

    def createSchedule(aggregate: ScheduleAggregate[F]): Schedule[F] =
      new DefaultSchedule(
        clock,
        aggregate,
        settings.bucketLength,
        aggregateJournal,
        offsetStore,
        eventTag
      )

    for {
      aggregate <- startAggregate
      _ <- startProcess(aggregate)
      schedule = createSchedule(aggregate)
    } yield schedule
  }

}
