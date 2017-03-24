package aecor.schedule

import java.time.{ Clock, LocalDateTime }
import java.util.UUID

import aecor.aggregate.CorrelationId
import aecor.aggregate.runtime.Async
import aecor.data.EventTag
import aecor.streaming._
import akka.NotUsed
import akka.stream.scaladsl.Source

import scala.concurrent.duration.FiniteDuration

private[schedule] class DefaultSchedule[F[_]: Async](
  clock: Clock,
  aggregate: ScheduleAggregate[F],
  bucketLength: FiniteDuration,
  aggregateJournal: AggregateJournal[UUID, ScheduleEvent],
  offsetStore: OffsetStore[F, UUID],
  eventTag: EventTag[ScheduleEvent]
) extends Schedule[F] {
  override def addScheduleEntry(scheduleName: String,
                                entryId: String,
                                correlationId: CorrelationId,
                                dueDate: LocalDateTime): F[Unit] = {
    val scheduleBucket =
      dueDate.atZone(clock.getZone).toEpochSecond / bucketLength.toSeconds
    aggregate
      .addScheduleEntry(scheduleName, scheduleBucket.toString, entryId, correlationId, dueDate)
  }

  override def committableScheduleEvents(
    scheduleName: String,
    consumerId: ConsumerId
  ): Source[Committable[F, JournalEntry[UUID, ScheduleEvent]], NotUsed] =
    aggregateJournal
      .committableEventsByTag(offsetStore, eventTag, ConsumerId(scheduleName + consumerId.value))
      .collect {
        case m if m.value.event.scheduleName == scheduleName => m
      }
}
