package aecor.schedule.process

import java.time.temporal.ChronoUnit
import java.time.{Clock => _, _}

import aecor.data._
import aecor.runtime.KeyValueStore
import aecor.schedule.ScheduleEvent.{ScheduleEntryAdded, ScheduleEntryFired}
import aecor.schedule.{ScheduleBucket, ScheduleBucketId, ScheduleEntryRepository}
import cats.Monad
import cats.implicits._

import scala.concurrent.duration.FiniteDuration

object ScheduleProcess {
  def apply[F[_]: Monad](journal: ScheduleEventJournal[F],
                         dayZero: LocalDate,
                         consumerId: ConsumerId,
                         offsetStore: KeyValueStore[F, TagConsumer, LocalDateTime],
                         eventualConsistencyDelay: FiniteDuration,
                         repository: ScheduleEntryRepository[F],
                         buckets: ScheduleBucketId => ScheduleBucket[F],
                         clock: F[LocalDateTime],
                         parallelism: Int): F[Unit] = {
    val scheduleEntriesTag = EventTag("io.aecor.ScheduleDueEntries")

    val tagConsumerId = TagConsumer(scheduleEntriesTag, consumerId)

    val updateRepository: F[Unit] =
      journal.processNewEvents {
        case EntityEvent(id, _, ScheduleEntryAdded(entryId, _, dueDate, _)) =>
          for {
            _ <- repository.insertScheduleEntry(id, entryId, dueDate)
            now <- clock
            _ <- if (dueDate.isEqual(now) || dueDate.isBefore(now)) {
                buckets(id).fireEntry(entryId)
              } else {
                ().pure[F]
              }
          } yield ()
        case EntityEvent(id, _, ScheduleEntryFired(entryId, _, _)) =>
          repository.markScheduleEntryAsFired(id, entryId)
      }
    def fireEntries(from: LocalDateTime,
                    to: LocalDateTime): F[Option[ScheduleEntryRepository.ScheduleEntry]] =
      repository.processEntries(from, to, parallelism) {
        entry =>
          if (entry.fired)
            ().pure[F]
          else
            buckets(entry.bucketId).fireEntry(entry.entryId)
      }

    val loadOffset: F[LocalDateTime] =
      offsetStore
        .getValue(tagConsumerId)
        .map(_.getOrElse(dayZero.atStartOfDay()))

    def saveOffset(value: LocalDateTime): F[Unit] =
      offsetStore.setValue(tagConsumerId, value)

    for {
      _ <- updateRepository
      from <- loadOffset
      now <- clock
      entry <- fireEntries(from.minus(eventualConsistencyDelay.toMillis, ChronoUnit.MILLIS), now)
      _ <- entry.map(_.dueDate).traverse(saveOffset)
    } yield ()
  }

}
