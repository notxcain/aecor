package aecor.schedule.process

import aecor.data.Identified
import aecor.schedule.{ ScheduleBucketId, ScheduleEvent }

trait ScheduleEventJournal[F[_]] {
  def processNewEvents(f: Identified[ScheduleBucketId, ScheduleEvent] => F[Unit]): F[Unit]
}
