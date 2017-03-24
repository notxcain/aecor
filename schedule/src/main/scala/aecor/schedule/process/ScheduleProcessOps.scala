package aecor.schedule.process

import java.time.LocalDateTime

import aecor.schedule.ScheduleEntryRepository.ScheduleEntry
import aecor.schedule.ScheduleEvent

trait ScheduleProcessOps[F[_]] {
  def loadOffset: F[LocalDateTime]
  def saveOffset(value: LocalDateTime): F[Unit]
  def now: F[LocalDateTime]
  def processNewEvents(f: ScheduleEvent => F[Unit]): F[Unit]
  def processEntries(from: LocalDateTime, to: LocalDateTime)(
    f: ScheduleEntry => F[Unit]
  ): F[Option[ScheduleEntry]]
}
