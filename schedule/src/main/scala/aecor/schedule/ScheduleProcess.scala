package aecor.schedule

import java.time._
import java.util.UUID

import aecor.data.EventTag
import aecor.schedule.ScheduleEntryRepository.ScheduleEntry
import aecor.schedule.ScheduleEvent.{ ScheduleEntryAdded, ScheduleEntryFired }
import aecor.streaming.StreamSupervisor.StreamKillSwitch
import aecor.streaming._
import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.Logging
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Sink, Source }
import cats.data.Reader
import com.datastax.driver.core.utils.UUIDs

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import cats.instances.future._

private[schedule] class ScheduleProcess(
  system: ActorSystem,
  clock: Clock,
  entityName: String,
  consumerId: ConsumerId,
  dayZero: LocalDate,
  refreshInterval: FiniteDuration,
  parallelism: Int,
  offsetStore: OffsetStore[UUID],
  repository: ScheduleEntryRepository,
  scheduleAggregate: ScheduleAggregate[Future],
  aggregateJournal: AggregateJournal[UUID],
  eventTag: EventTag[ScheduleEvent]
)(implicit materializer: Materializer) {

  import materializer.executionContext

  val log = Logging(system, classOf[ScheduleProcess])

  val schduleEntriesTag = "io.aecor.ScheduleDueEntries"

  private def updateRepository: Future[Int] =
    aggregateJournal
      .committableCurrentEventsByTag(offsetStore, eventTag, consumerId)
      .map(_.map(_.event))
      .mapAsync(parallelism)(_.traverse {
        case ScheduleEntryAdded(scheduleName, scheduleBucket, entryId, _, dueDate, _) =>
          repository
            .insertScheduleEntry(scheduleName, scheduleBucket, entryId, dueDate)
            .map(_ => 1)
        case ScheduleEntryFired(scheduleName, scheduleBucket, entryId, _, _) =>
          repository.markScheduleEntryAsFired(scheduleName, scheduleBucket, entryId).map(_ => 0)
      })
      .mapAsync(1)(x => x.commit().map(_ => x.value))
      .runWith(Sink.fold(0)(_ + _))

  private def source =
    Source
      .single(())
      .mapAsync(1)(_ => offsetStore.getOffset(schduleEntriesTag, consumerId))
      .map {
        case Some(offset) =>
          LocalDateTime.ofInstant(Instant.ofEpochMilli(UUIDs.unixTimestamp(offset)), clock.getZone)
        case None =>
          dayZero.atStartOfDay()
      }
      .flatMapConcat(entriesFrom)

  private def entriesFrom(from: LocalDateTime): Source[Committable[ScheduleEntry], NotUsed] =
    Source
      .single(())
      .mapAsync(1) { _ =>
        updateRepository
      }
      .flatMapConcat { updatedCounter =>
        log.debug(s"Schedule entries view updated, new entries = [$updatedCounter]")
        val now = LocalDateTime.now(clock)
        repository
          .getEntries(from, now)
          .map { entry =>
            val offset = UUIDs.startOf(entry.dueDate.atZone(clock.getZone).toInstant.toEpochMilli)
            Committable(() => offsetStore.setOffset(schduleEntriesTag, consumerId, offset), entry)
          }
          .concat(
            Source
              .tick(refreshInterval, refreshInterval, ())
              .take(1)
              .flatMapConcat { _ =>
                entriesFrom(now)
              }
          )
      }

  private val fireDueEntries = Flow[Committable[ScheduleEntry]]
    .mapAsync(parallelism)(_.traverse { entry =>
      if (entry.fired)
        Future.successful(())
      else
        scheduleAggregate.fireEntry(entry.scheduleName, entry.scheduleBucket, entry.entryId)
    })
    .mapAsync(1)(_.commit())

  def run: Reader[Unit, StreamKillSwitch] = Reader { _ =>
    StreamSupervisor(system)
      .startClusterSingleton(s"$entityName-Process", source, fireDueEntries)
  }
}

object ScheduleProcess {
  def apply(
    system: ActorSystem,
    clock: Clock,
    entityName: String,
    consumerId: ConsumerId,
    dayZero: LocalDate,
    refreshInterval: FiniteDuration,
    parallelism: Int,
    offsetStore: OffsetStore[UUID],
    repository: ScheduleEntryRepository,
    scheduleAggregate: ScheduleAggregate[Future],
    aggregateJournal: AggregateJournal[UUID],
    eventTag: EventTag[ScheduleEvent]
  )(implicit materializer: Materializer): ScheduleProcess =
    new ScheduleProcess(
      system,
      clock,
      entityName,
      consumerId,
      dayZero,
      refreshInterval,
      parallelism,
      offsetStore,
      repository,
      scheduleAggregate,
      aggregateJournal,
      eventTag
    )
}
