package aecor.schedule.process

import java.time._
import java.time.temporal.ChronoUnit

import aecor.schedule.ScheduleEvent.{ ScheduleEntryAdded, ScheduleEntryFired }
import aecor.schedule.{ ScheduleAggregate, ScheduleEntryRepository }
import aecor.streaming._
import cats.Monad
import cats.implicits._

import scala.concurrent.duration.FiniteDuration

object ScheduleProcess {
  def apply[F[_]: Monad](journal: ScheduleEventJournal[F],
                         dayZero: LocalDate,
                         consumerId: ConsumerId,
                         offsetStore: OffsetStore[F, LocalDateTime],
                         eventualConsistencyDelay: FiniteDuration,
                         repository: ScheduleEntryRepository[F],
                         scheduleAggregate: ScheduleAggregate[F],
                         clock: F[LocalDateTime],
                         parallelism: Int): F[Unit] = {
    val scheduleEntriesTag = "io.aecor.ScheduleDueEntries"

    def updateRepository: F[Unit] =
      journal.processNewEvents {
        case ScheduleEntryAdded(scheduleName, scheduleBucket, entryId, _, dueDate, _) =>
          repository
            .insertScheduleEntry(scheduleName, scheduleBucket, entryId, dueDate)
        case ScheduleEntryFired(scheduleName, scheduleBucket, entryId, _, _) =>
          repository.markScheduleEntryAsFired(scheduleName, scheduleBucket, entryId)
      }
    def fireEntries(from: LocalDateTime,
                    to: LocalDateTime): F[Option[ScheduleEntryRepository.ScheduleEntry]] =
      repository.processEntries(from, to, parallelism) { entry =>
        if (entry.fired)
          ().pure[F]
        else
          scheduleAggregate
            .fireEntry(entry.scheduleName, entry.scheduleBucket, entry.entryId)
      }

    def loadOffset: F[LocalDateTime] =
      offsetStore
        .getOffset(scheduleEntriesTag, consumerId)
        .map(_.getOrElse(dayZero.atStartOfDay()))

    def saveOffset(value: LocalDateTime): F[Unit] =
      offsetStore.setOffset(scheduleEntriesTag, consumerId, value)

    for {
      _ <- updateRepository
      from <- loadOffset
      now <- clock
      entry <- fireEntries(from.minus(eventualConsistencyDelay.toMillis, ChronoUnit.MILLIS), now)
      _ <- entry.map(_.dueDate).traverse(saveOffset)
    } yield ()
  }

}
