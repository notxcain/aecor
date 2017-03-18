package aecor.schedule

import java.time._
import java.time.temporal.ChronoUnit
import java.util.UUID

import aecor.aggregate.runtime.{ Async, Capture, CaptureFuture }
import aecor.data.EventTag
import aecor.schedule.ScheduleEvent.{ ScheduleEntryAdded, ScheduleEntryFired }
import aecor.streaming.StreamSupervisor.StreamKillSwitch
import aecor.streaming._
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Sink, Source }
import cats.Functor
import com.datastax.driver.core.utils.UUIDs
import org.slf4j.LoggerFactory
import Async.ops._

import scala.concurrent.{ Future, Promise }
import scala.concurrent.duration.FiniteDuration

private[schedule] class ScheduleProcess[F[_]: Async: CaptureFuture: Capture: Functor](
  clock: Clock,
  entityName: String,
  consumerId: ConsumerId,
  dayZero: LocalDate,
  refreshInterval: FiniteDuration,
  eventualConsistencyDelay: FiniteDuration,
  parallelism: Int,
  offsetStore: OffsetStore[UUID],
  repository: ScheduleEntryRepository,
  scheduleAggregate: ScheduleAggregate[F],
  aggregateJournal: AggregateJournal[UUID],
  eventTag: EventTag[ScheduleEvent]
)(implicit materializer: Materializer) {

  import materializer.executionContext

  private val log = LoggerFactory.getLogger(classOf[ScheduleProcess[F]])

  private val scheduleEntriesTag = "io.aecor.ScheduleDueEntries"

  private def source =
    Source
      .single(())
      .mapAsync(1)(_ => offsetStore.getOffset(scheduleEntriesTag, consumerId))
      .map {
        case Some(offset) =>
          LocalDateTime.ofInstant(Instant.ofEpochMilli(UUIDs.unixTimestamp(offset)), clock.getZone)
        case None =>
          dayZero.atStartOfDay()
      }
      .mapAsync(1)(runProcessCycle)

  private def runProcessCycle(from: LocalDateTime): Future[Unit] =
    for {
      updatedCounter <- updateRepository
      _ = log.debug(s"Schedule entries view updated, new entries = [$updatedCounter]")
      now = LocalDateTime.now(clock)
      _ <- fireEntries(from, now)
      _ <- afterRefreshInterval {
            runProcessCycle(now.minus(eventualConsistencyDelay.toMillis, ChronoUnit.MILLIS))
          }
    } yield ()

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
      .fold(Committable.pure(0)) { (acc, x) =>
        x.copy(value = acc.value + x.value)
      }
      .mapAsync(1)(x => x.commit().map(_ => x.value))
      .runWith(Sink.head)

  private def fireEntries(from: LocalDateTime, to: LocalDateTime) =
    repository
      .getEntries(from, to)
      .map { entry =>
        val offset = UUIDs.startOf(entry.dueDate.atZone(clock.getZone).toInstant.toEpochMilli)
        Committable(() => offsetStore.setOffset(scheduleEntriesTag, consumerId, offset), entry)
      }
      .mapAsync(parallelism)(_.traverse { entry =>
        if (entry.fired)
          Future.successful(())
        else
          scheduleAggregate
            .fireEntry(entry.scheduleName, entry.scheduleBucket, entry.entryId)
            .unsafeRun

      })
      .mapAsync(1)(_.commit())
      .runWith(Sink.ignore)

  private def afterRefreshInterval[A](f: => Future[A]): Future[A] = {
    val p = Promise[A]
    materializer.scheduleOnce(refreshInterval, new Runnable {
      override def run(): Unit = p.completeWith(f)
    })
    p.future
  }

  def run(system: ActorSystem): F[StreamKillSwitch[F]] =
    StreamSupervisor(system)
      .startClusterSingleton(s"$entityName-Process", source, Flow[Unit])

}

object ScheduleProcess {
  def apply[F[_]: Async: CaptureFuture: Capture: Functor](
    clock: Clock,
    entityName: String,
    consumerId: ConsumerId,
    dayZero: LocalDate,
    refreshInterval: FiniteDuration,
    eventualConsistencyDelay: FiniteDuration,
    parallelism: Int,
    offsetStore: OffsetStore[UUID],
    repository: ScheduleEntryRepository,
    scheduleAggregate: ScheduleAggregate[F],
    aggregateJournal: AggregateJournal[UUID],
    eventTag: EventTag[ScheduleEvent]
  )(implicit materializer: Materializer): ScheduleProcess[F] =
    new ScheduleProcess(
      clock,
      entityName,
      consumerId,
      dayZero,
      refreshInterval,
      eventualConsistencyDelay,
      parallelism,
      offsetStore,
      repository,
      scheduleAggregate,
      aggregateJournal,
      eventTag
    )
}
