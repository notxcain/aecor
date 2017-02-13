package aecor.schedule

import java.time._
import java.util.UUID

import aecor.schedule.ScheduleEntryRepository.ScheduleEntry
import aecor.schedule.ScheduleEvent.ScheduleEntryAdded
import aecor.streaming.StreamSupervisor.StreamKillSwitch
import aecor.streaming.{ Committable, ConsumerId, OffsetStore, StreamSupervisor }
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Sink, Source }
import com.datastax.driver.core.utils.UUIDs

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import cats.instances.future._

class ScheduleProcess(system: ActorSystem,
                      entityName: String,
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

  val tag = "io.aecor.ScheduleDueEntries"

  private val dueEntries = Source
    .single(())
    .mapAsync(1)(_ => offsetStore.getOffset(tag, consumerId))
    .map {
      case Some(offset) =>
        LocalDateTime.ofInstant(Instant.ofEpochMilli(UUIDs.unixTimestamp(offset)), clock.getZone)
      case None =>
        dayZero.atStartOfDay()
    }
    .flatMapConcat { offset =>
      repository.dueEntries(offset, tickInterval, clock).map { entry =>
        val offset = UUIDs.startOf(entry.dueDate.atZone(clock.getZone).toInstant.toEpochMilli)
        Committable(() => offsetStore.setOffset(tag, consumerId, offset), entry)
      }
    }

  private val fireDueEntries = Flow[Committable[ScheduleEntry]]
    .mapAsync(parallelism)(_.traverse { entry =>
      scheduleAggregate.fireEntry(entry.scheduleName, entry.scheduleBucket, entry.entryId)
    })
    .mapAsync(1)(_.commit())

  val added = new java.util.concurrent.atomic.AtomicInteger(0)
  val total = new java.util.concurrent.atomic.AtomicInteger(0)
  private val entryAddedEvents = eventSource(consumerId)
    .map { x =>
      println(s"Event processed: ${total.incrementAndGet()}")
      x
    }
    .collect {
      case Committable(c, e: ScheduleEntryAdded) =>
        Committable(c, e)
    }

  Source
    .single(())
    .mapAsync(1) { _ =>
      entryAddedEvents.via(addEntry).runWith(Sink.ignore)
    }
    .flatMapConcat { _ =>
      dueEntries.via(fireDueEntries)
    }

  private val addEntry = Flow[Committable[ScheduleEntryAdded]]
    .mapAsync(parallelism)(_.traverse {
      case ScheduleEntryAdded(scheduleName, scheduleBucket, entryId, _, dueDate) =>
        for {
          _ <- repository.insertScheduleEntry(scheduleName, scheduleBucket, entryId, dueDate)
          _ = println(s"Entries added: ${added.incrementAndGet()}")
        } yield ()
    })
    .mapAsync(1)(_.commit())

  def run(): StreamKillSwitch =
    StreamSupervisor(system)
      .startClusterSingleton(s"$entityName-DueEntries", dueEntries, fireDueEntries)
      .and(
        StreamSupervisor(system)
          .startClusterSingleton(s"$entityName-ScheduleEntries", entryAddedEvents, addEntry)
      )
}
