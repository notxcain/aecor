package aecor.schedule

import java.time.LocalDateTime
import java.util.UUID

import aecor.data._
import aecor.effect.Async
import aecor.effect.Async.ops._
import aecor.runtime.akkapersistence.{ CommittableEventJournalQuery, JournalEntry }
import aecor.util.Clock
import akka.NotUsed
import akka.stream.scaladsl.Source
import cats.Monad
import cats.implicits._
import scala.concurrent.duration.FiniteDuration

private[schedule] class DefaultSchedule[F[_]: Async: Monad](
  clock: Clock[F],
  aggregate: ScheduleAggregate[F],
  bucketLength: FiniteDuration,
  aggregateJournal: CommittableEventJournalQuery[F, UUID, ScheduleEvent],
  eventTag: EventTag
) extends Schedule[F] {
  override def addScheduleEntry(scheduleName: String,
                                entryId: String,
                                correlationId: CorrelationId,
                                dueDate: LocalDateTime): F[Unit] =
    for {
      zone <- clock.zone
      scheduleBucket = dueDate.atZone(zone).toEpochSecond / bucketLength.toSeconds
      _ <- aggregate
            .addScheduleEntry(
              scheduleName,
              scheduleBucket.toString,
              entryId,
              correlationId,
              dueDate
            )
    } yield ()

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
