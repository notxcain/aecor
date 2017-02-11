package aecor.schedule

import java.time._
import java.util.UUID

import aecor.schedule.ScheduleEntryRepository.ScheduleEntryView
import aecor.schedule.aggregate.{ ScheduleAggregate, ScheduleEvent }
import aecor.schedule.aggregate.ScheduleEvent.ScheduleEntryAdded
import aecor.streaming.StreamSupervisor.StreamKillSwitch
import aecor.streaming.{ Committable, ConsumerId, OffsetStore, StreamSupervisor }
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Source }
import com.datastax.driver.core.utils.UUIDs
import cats.instances.future._
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

class ScheduleProcess(system: ActorSystem,
                      consumerId: ConsumerId,
                      dayZero: LocalDate,
                      tickInterval: FiniteDuration,
                      parallelism: Int,
                      offsetStore: OffsetStore[UUID],
                      repository: ScheduleEntryRepository,
                      scheduleAggregate: ScheduleAggregate[Future],
                      eventSource: ConsumerId => Source[Committable[ScheduleEvent], NotUsed],
                      clock: Clock)(implicit materializer: Materializer) {

  import materializer.executionContext

  private val source1 = Source
    .lazily(() => Source.fromFuture(offsetStore.getOffset("ScheduleEntries", consumerId)))
    .map {
      case Some(offset) =>
        LocalDateTime.ofInstant(Instant.ofEpochMilli(UUIDs.unixTimestamp(offset)), clock.getZone)
      case None =>
        dayZero.atStartOfDay()
    }
    .flatMapConcat { offset =>
      def rec(from: LocalDateTime): Source[Committable[ScheduleEntryView], NotUsed] = {
        val to: LocalDateTime = LocalDateTime.now(clock)
        repository
          .getEntries(from, to)
          .map { entry =>
            val offset = UUIDs.startOf(entry.dueDate.atZone(clock.getZone).toInstant.toEpochMilli)
            Committable(() => offsetStore.setOffset("ScheduleEntries", consumerId, offset), entry)
          }
          .concat(
            Source
              .tick(tickInterval, tickInterval, ())
              .take(1)
              .flatMapConcat { _ =>
                rec(to)
              }
          )
      }
      rec(offset)
    }
    .mapAsync(parallelism) { c =>
      c.traverse { entry =>
        scheduleAggregate.fireEntry(entry.scheduleName, entry.entryId)
      }
    }

  def processEvent: ScheduleEvent => Future[Unit] = {
    case ScheduleEntryAdded(scheduleName, entryId, _, dueDate) =>
      if (dueDate.isEqual(LocalDateTime.now(clock)) || dueDate.isBefore(LocalDateTime.now(clock))) {
        scheduleAggregate.fireEntry(scheduleName, entryId)
      } else {
        repository.insertScheduleEntry(scheduleName, entryId, dueDate)
      }
    case _ =>
      Future.successful(())
  }

  private val source2 = eventSource(consumerId)
    .mapAsync(8)(_.traverse(processEvent))

  private val flow = Flow[Committable[Unit]].mapAsync(1)(_.commit())

  def run(): StreamKillSwitch =
    StreamSupervisor(system).startClusterSingleton(
      consumerId.value,
      source1.merge(source2, eagerComplete = true),
      flow
    )
}
