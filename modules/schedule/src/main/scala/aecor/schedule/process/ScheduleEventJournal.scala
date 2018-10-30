package aecor.schedule.process

import aecor.data.EntityEvent
import aecor.schedule.{ ScheduleBucketId, ScheduleEvent }

trait ScheduleEventJournal[F[_]] {
  def processNewEvents(f: EntityEvent[ScheduleBucketId, ScheduleEvent] => F[Unit]): F[Unit]
}
