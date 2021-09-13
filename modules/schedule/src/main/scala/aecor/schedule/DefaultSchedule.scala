package aecor.schedule

import java.time.LocalDateTime
import java.util.UUID

import aecor.data._
import aecor.runtime.akkapersistence.readside.{ CommittableEventJournalQuery, JournalEntry }
import aecor.util.Clock
import akka.NotUsed
import akka.stream.scaladsl.Source
import cats.effect.kernel.Async
import cats.effect.std.Dispatcher
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

private[schedule] class DefaultSchedule[F[_]: Async](
  clock: Clock[F],
  buckets: ScheduleBucketId => ScheduleBucket[F],
  bucketLength: FiniteDuration,
  aggregateJournal: CommittableEventJournalQuery[F, UUID, ScheduleBucketId, ScheduleEvent],
  eventTag: EventTag,
  dispatcher: Dispatcher[F]
) extends Schedule[F] {
  override def addScheduleEntry(scheduleName: String,
                                entryId: String,
                                correlationId: String,
                                dueDate: LocalDateTime): F[Unit] =
    for {
      zone <- clock.zone
      scheduleBucket = dueDate.atZone(zone).toEpochSecond / bucketLength.toSeconds
      _ <- buckets(ScheduleBucketId(scheduleName, scheduleBucket.toString))
            .addScheduleEntry(entryId, correlationId, dueDate)
    } yield ()

  override def committableScheduleEvents(
    scheduleName: String,
    consumerId: ConsumerId
  ): Source[Committable[F, JournalEntry[UUID, ScheduleBucketId, ScheduleEvent]], NotUsed] =
    aggregateJournal
      .eventsByTag(eventTag, ConsumerId(scheduleName + consumerId.value))
      .flatMapConcat {
        case m if m.value.event.entityKey.scheduleName == scheduleName => Source.single(m)
        case other =>
          Source
            .fromFuture(dispatcher.unsafeToFuture(other.commit))
            .flatMapConcat(
              _ => Source.empty[Committable[F, JournalEntry[UUID, ScheduleBucketId, ScheduleEvent]]]
            )
      }
}

object DefaultSchedule {
  def apply[F[_]: Async](
    clock: Clock[F],
    buckets: ScheduleBucketId => ScheduleBucket[F],
    bucketLength: FiniteDuration,
    aggregateJournal: CommittableEventJournalQuery[F, UUID, ScheduleBucketId, ScheduleEvent],
    eventTag: EventTag
  ): F[Schedule[F]] =
    Dispatcher[F].allocated
      .map(_._1)
      .map(
        dispatcher =>
          new DefaultSchedule(clock, buckets, bucketLength, aggregateJournal, eventTag, dispatcher)
      )
}
