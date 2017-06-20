package aecor.schedule.process

import java.time.temporal.ChronoUnit
import java.time.{ Clock => _, _ }

import aecor.data.{ ConsumerId, TagConsumerId }
import aecor.schedule.ScheduleEvent.{ ScheduleEntryAdded, ScheduleEntryFired }
import aecor.schedule.{ ScheduleAggregate, ScheduleEntryRepository }
import aecor.util.KeyValueStore
import cats.Monad
import cats.implicits._

import scala.concurrent.duration.FiniteDuration

object ScheduleProcess {
  def apply[F[_]: Monad](journal: ScheduleEventJournal[F],
                         dayZero: LocalDate,
                         consumerId: ConsumerId,
                         offsetStore: KeyValueStore[F, TagConsumerId, LocalDateTime],
                         eventualConsistencyDelay: FiniteDuration,
                         repository: ScheduleEntryRepository[F],
                         scheduleAggregate: ScheduleAggregate[F],
                         clock: F[LocalDateTime],
                         parallelism: Int): F[Unit] = {
    val scheduleEntriesTag = "io.aecor.ScheduleDueEntries"

    val tagConsumerId = TagConsumerId(scheduleEntriesTag, consumerId)

    val updateRepository: F[Unit] =
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
