package aecor.schedule.process

import aecor.schedule.ScheduleEvent

trait ScheduleEventJournal[F[_]] {
  def processNewEvents(f: ScheduleEvent => F[Unit]): F[Unit]
}
