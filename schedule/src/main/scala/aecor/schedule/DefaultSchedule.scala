package aecor.schedule

import java.time.{ Clock, LocalDateTime }
import java.util.UUID

import aecor.aggregate.CorrelationId
import aecor.data.EventTag
import aecor.streaming._
import akka.NotUsed
import akka.stream.scaladsl.Source

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

class DefaultSchedule(clock: Clock,
                      bucketLength: FiniteDuration,
                      aggregate: ScheduleAggregate[Future],
                      aggregateJournal: AggregateJournal[UUID],
                      offsetStore: OffsetStore[UUID],
                      eventTag: EventTag[ScheduleEvent])
    extends Schedule {
  override def addScheduleEntry(scheduleName: String,
                                entryId: String,
                                correlationId: CorrelationId,
                                dueDate: LocalDateTime): Future[Unit] = {
    val scheduleBucket =
      dueDate.atZone(clock.getZone).toEpochSecond / bucketLength.toSeconds
    aggregate
      .addScheduleEntry(scheduleName, scheduleBucket.toString, entryId, correlationId, dueDate)
  }

  override def committableScheduleEvents(
    scheduleName: String,
    consumerId: ConsumerId
  ): Source[Committable[JournalEntry[UUID, ScheduleEvent]], NotUsed] =
    aggregateJournal
      .committableEventsByTag[ScheduleEvent](
        offsetStore,
        eventTag,
        ConsumerId(scheduleName + consumerId.value)
      )
      .collect {
        case m if m.value.event.scheduleName == scheduleName => m
      }
}
