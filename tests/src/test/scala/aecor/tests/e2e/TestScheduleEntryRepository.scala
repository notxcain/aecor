package aecor.tests.e2e

import java.time.LocalDateTime

import aecor.schedule.CassandraScheduleEntryRepository.TimeBucket
import aecor.schedule.ScheduleEntryRepository
import aecor.schedule.ScheduleEntryRepository.ScheduleEntry
import cats.Monad
import cats.data.StateT
import cats.implicits.none
import cats.implicits._

object TestScheduleEntryRepository {
  def apply[F[_]: Monad, S](
    extract: S => Vector[ScheduleEntry],
    update: (S, Vector[ScheduleEntry]) => S
  ): ScheduleEntryRepository[StateT[F, S, ?]] =
    new TestScheduleEntryRepository(extract, update)
}

class TestScheduleEntryRepository[F[_]: Monad, S](extract: S => Vector[ScheduleEntry],
                                                  update: (S, Vector[ScheduleEntry]) => S)
    extends ScheduleEntryRepository[StateT[F, S, ?]] {
  override def insertScheduleEntry(scheduleName: String,
                                   scheduleBucket: String,
                                   entryId: String,
                                   dueDate: LocalDateTime): StateT[F, S, Unit] =
    StateT
      .modify[F, Vector[ScheduleEntry]] { scheduleEntries =>
        scheduleEntries :+ ScheduleEntry(
          scheduleName,
          scheduleBucket,
          entryId,
          dueDate,
          TimeBucket(dueDate.toLocalDate).key,
          false
        )
      }
      .transformS(extract, update)
  override def markScheduleEntryAsFired(scheduleName: String,
                                        scheduleBucket: String,
                                        entryId: String): StateT[F, S, Unit] =
    StateT
      .modify[F, Vector[ScheduleEntry]] { scheduleEntries =>
        scheduleEntries.map { e =>
          if (e.scheduleName == scheduleName && scheduleBucket == e.scheduleBucket && e.entryId == entryId) {
            e.copy(fired = true)
          } else {
            e
          }
        }
      }
      .transformS(extract, update)

  override def processEntries(from: LocalDateTime, to: LocalDateTime, parallelism: Int)(
    f: (ScheduleEntryRepository.ScheduleEntry) => StateT[F, S, Unit]
  ): StateT[F, S, Option[ScheduleEntryRepository.ScheduleEntry]] =
    StateT
      .get[F, Vector[ScheduleEntry]]
      .map { entries =>
        val x: StateT[F, S, Option[ScheduleEntry]] = entries
          .foldLeft(
            StateT
              .pure[F, S, Option[ScheduleEntryRepository.ScheduleEntry]](none)
          ) { (acc, entry) =>
            if (entry.dueDate.isAfter(from) && (entry.dueDate
                  .isBefore(to) || entry.dueDate == to)) {
              acc.flatMap(_ => f(entry)).map(_ => entry.some)
            } else {
              acc
            }
          }
        x
      }
      .transformS(extract, update)
      .flatten

}
