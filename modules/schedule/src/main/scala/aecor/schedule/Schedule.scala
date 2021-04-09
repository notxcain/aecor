package aecor.schedule

import java.time.{ Clock => _, _ }
import java.util.UUID

import aecor.data._
import aecor.runtime.KeyValueStore
import aecor.runtime.akkapersistence._
import aecor.runtime.akkapersistence.readside.JournalEntry
import aecor.schedule.process.{
  DefaultScheduleEventJournal,
  PeriodicProcessRuntime,
  ScheduleProcess
}
import aecor.util.Clock
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import cats.effect.{ ContextShift, Effect }
import cats.implicits._
import com.datastax.driver.core.utils.UUIDs

import scala.concurrent.duration._

trait Schedule[F[_]] {
  def addScheduleEntry(scheduleName: String,
                       entryId: String,
                       correlationId: String,
                       dueDate: LocalDateTime): F[Unit]
  def committableScheduleEvents(
    scheduleName: String,
    consumerId: ConsumerId
  ): Source[Committable[F, JournalEntry[UUID, ScheduleBucketId, ScheduleEvent]], NotUsed]
}

object Schedule {
  final case class ScheduleSettings(bucketLength: FiniteDuration,
                                    refreshInterval: FiniteDuration,
                                    eventualConsistencyDelay: FiniteDuration,
                                    consumerId: ConsumerId)

  def start[F[_]: Effect: ContextShift](
    entityName: String,
    dayZero: LocalDate,
    clock: Clock[F],
    repository: ScheduleEntryRepository[F],
    offsetStore: KeyValueStore[F, TagConsumer, UUID],
    settings: ScheduleSettings = ScheduleSettings(
      1.day,
      10.seconds,
      40.seconds,
      ConsumerId("io.aecor.schedule.ScheduleProcess")
    )
  )(implicit system: ActorSystem, materializer: Materializer): F[Schedule[F]] = {

    val eventTag = EventTag(entityName)

    val runtime = AkkaPersistenceRuntime(system, CassandraJournalAdapter(system))

    def uuidToLocalDateTime(zoneId: ZoneId): KeyValueStore[F, TagConsumer, LocalDateTime] =
      offsetStore.imap(
        uuid => LocalDateTime.ofInstant(Instant.ofEpochMilli(UUIDs.unixTimestamp(uuid)), zoneId)
      )(value => UUIDs.startOf(value.atZone(zoneId).toInstant.toEpochMilli))

    def deployBuckets =
      runtime
        .deploy(
          entityName,
          DefaultScheduleBucket.behavior(clock.zonedDateTime),
          Tagging.const[ScheduleBucketId](eventTag)
        )

    def startProcess(buckets: ScheduleBucketId => ScheduleBucket[F]) = clock.zone.flatMap { zone =>
      val journal =
        DefaultScheduleEventJournal[F](
          consumerId = settings.consumerId,
          parallelism = 8,
          aggregateJournal = runtime.journal[ScheduleBucketId, ScheduleEvent].committable(offsetStore),
          eventTag = eventTag
        )

      val process = ScheduleProcess(
        journal = journal,
        dayZero = dayZero,
        consumerId = settings.consumerId,
        offsetStore = uuidToLocalDateTime(zone),
        eventualConsistencyDelay = settings.eventualConsistencyDelay,
        repository = repository,
        buckets = buckets,
        clock = clock.localDateTime,
        parallelism = 8
      )

      PeriodicProcessRuntime(
        name = entityName,
        tickInterval = settings.refreshInterval,
        processCycle = process
      ).run(system)
    }

    def createSchedule(buckets: ScheduleBucketId => ScheduleBucket[F]): Schedule[F] =
      new DefaultSchedule(
        clock,
        buckets,
        settings.bucketLength,
        runtime.journal[ScheduleBucketId, ScheduleEvent].committable(offsetStore),
        eventTag
      )

    for {
      buckets <- deployBuckets
      _ <- startProcess(buckets)
    } yield createSchedule(buckets)
  }

}
