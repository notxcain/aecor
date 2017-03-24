package aecor.schedule

import java.time.LocalDateTime

import aecor.schedule.ScheduleEntryRepository.ScheduleEntry

trait ScheduleEntryRepository[F[_]] {
  def insertScheduleEntry(scheduleName: String,
                          scheduleBucket: String,
                          entryId: String,
                          dueDate: LocalDateTime): F[Unit]
  def markScheduleEntryAsFired(scheduleName: String,
                               scheduleBucket: String,
                               entryId: String): F[Unit]
  def processEntries(from: LocalDateTime, to: LocalDateTime, parallelism: Int)(
    f: (ScheduleEntry) => F[Unit]
  ): F[Option[ScheduleEntry]]
}

object ScheduleEntryRepository {
  final case class ScheduleEntry(scheduleName: String,
                                 scheduleBucket: String,
                                 entryId: String,
                                 dueDate: LocalDateTime,
                                 timeBucket: String,
                                 fired: Boolean)
}
