package aecor.schedule.process

import java.time._
import java.util.UUID

import aecor.aggregate.runtime.Async.ops._
import aecor.aggregate.runtime.{ Async, Capture, CaptureFuture }
import aecor.data.EventTag
import aecor.schedule.ScheduleEntryRepository.ScheduleEntry
import aecor.schedule.{ ScheduleAggregate, ScheduleEntryRepository, ScheduleEvent }
import aecor.streaming._
import akka.stream.Materializer
import akka.stream.scaladsl.{ Keep, Sink }
import cats.Monad
import cats.implicits._
import com.datastax.driver.core.utils.UUIDs

object DefaultScheduleProcessOps {
  def apply[F[_]: Async: CaptureFuture: Capture: Monad](
    clock: Clock,
    consumerId: ConsumerId,
    parallelism: Int,
    offsetStore: OffsetStore[F, UUID],
    dayZero: LocalDate,
    repository: ScheduleEntryRepository[F],
    scheduleAggregate: ScheduleAggregate[F],
    aggregateJournal: AggregateJournal[UUID, ScheduleEvent],
    eventTag: EventTag[ScheduleEvent]
  )(implicit materializer: Materializer): DefaultScheduleProcessOps[F] =
    new DefaultScheduleProcessOps(
      clock,
      consumerId,
      parallelism,
      offsetStore,
      dayZero,
      repository,
      scheduleAggregate,
      aggregateJournal,
      eventTag
    )
}

class DefaultScheduleProcessOps[F[_]: Async: CaptureFuture: Capture: Monad](
  clock: Clock,
  consumerId: ConsumerId,
  parallelism: Int,
  offsetStore: OffsetStore[F, UUID],
  dayZero: LocalDate,
  repository: ScheduleEntryRepository[F],
  scheduleAggregate: ScheduleAggregate[F],
  aggregateJournal: AggregateJournal[UUID, ScheduleEvent],
  eventTag: EventTag[ScheduleEvent]
)(implicit materializer: Materializer)
    extends ScheduleProcessOps[F] {
  private val scheduleEntriesTag = "io.aecor.ScheduleDueEntries"

  import materializer.executionContext

  override def now: F[LocalDateTime] = Capture[F].capture(LocalDateTime.now(clock))

  override def loadOffset: F[LocalDateTime] =
    offsetStore.getOffset(scheduleEntriesTag, consumerId).map {
      case Some(uuid) =>
        LocalDateTime.ofInstant(Instant.ofEpochMilli(UUIDs.unixTimestamp(uuid)), clock.getZone)
      case None =>
        dayZero.atStartOfDay()
    }

  override def saveOffset(value: LocalDateTime): F[Unit] = {
    val offset = UUIDs.startOf(value.atZone(clock.getZone).toInstant.toEpochMilli)
    offsetStore.setOffset(scheduleEntriesTag, consumerId, offset)
  }

  override def processNewEvents(f: (ScheduleEvent) => F[Unit]): F[Unit] =
    CaptureFuture[F].captureF {
      aggregateJournal
        .committableCurrentEventsByTag(offsetStore, eventTag, consumerId)
        .mapAsync(parallelism)(_.map(_.event).traverse(f.andThen(_.unsafeRun)))
        .fold(Committable.unit[F])(Keep.right)
        .mapAsync(1)(_.commit().unsafeRun)
        .runWith(Sink.ignore)
    }.void

  override def processEntries(from: LocalDateTime, to: LocalDateTime)(
    f: (ScheduleEntry) => F[Unit]
  ): F[Option[ScheduleEntry]] =
    repository.processEntries(from, to, parallelism)(f)
}
