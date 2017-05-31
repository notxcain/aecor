package aecor.schedule

import java.time.{ Clock, LocalDateTime }
import java.util.UUID

import aecor.data._
import aecor.effect.Async
import aecor.effect.Async.ops._
import aecor.runtime.akkapersistence.{ CommittableEventJournalQuery, JournalEntry }
import akka.NotUsed
import akka.stream.scaladsl.Source

import scala.concurrent.duration.FiniteDuration

private[schedule] class DefaultSchedule[F[_]: Async](
  clock: Clock,
  aggregate: ScheduleAggregate[F],
  bucketLength: FiniteDuration,
  aggregateJournal: CommittableEventJournalQuery[F, UUID, ScheduleEvent],
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
      .eventsByTag(eventTag, ConsumerId(scheduleName + consumerId.value))
      .flatMapConcat {
        case m if m.value.event.scheduleName == scheduleName => Source.single(m)
        case other =>
          Source
            .fromFuture(other.commit.unsafeRun)
            .flatMapConcat(_ => Source.empty[Committable[F, JournalEntry[UUID, ScheduleEvent]]])
      }

}
