package aecor.schedule

import java.time.{ Clock, LocalDateTime }

import aecor.aggregate.{ Correlation, CorrelationId }
import aecor.data.Handler
import aecor.schedule.ScheduleEvent.{ ScheduleEntryAdded, ScheduleEntryFired }
import scala.collection.immutable.Seq

object DefaultScheduleAggregate {

  def apply(clock: Clock): ScheduleAggregate[Handler[ScheduleState, Seq[ScheduleEvent], ?]] =
    new DefaultScheduleAggregate(clock)

  def correlation: Correlation[ScheduleOp] =
    c => CorrelationId.composite("-", c.scheduleName, c.scheduleBucket)

}

class DefaultScheduleAggregate(clock: Clock)
    extends ScheduleAggregate[Handler[ScheduleState, Seq[ScheduleEvent], ?]] {

  private def timestamp = clock.instant()

  override def addScheduleEntry(
    scheduleName: String,
    scheduleBucket: String,
    entryId: String,
    correlationId: CorrelationId,
    dueDate: LocalDateTime
  ): Handler[ScheduleState, Seq[ScheduleEvent], Unit] =
    Handler { state =>
      if (state.entries.get(entryId).isDefined) {
        Vector.empty -> (())
      } else {
        val scheduleEntryAdded = ScheduleEntryAdded(
          scheduleName,
          scheduleBucket,
          entryId,
          correlationId,
          dueDate,
          timestamp
        )
        val now = LocalDateTime.now(clock)
        val firedEvent = if (dueDate.isEqual(now) || dueDate.isBefore(now)) {
          Vector(
            ScheduleEntryFired(scheduleName, scheduleBucket, entryId, correlationId, timestamp)
          )
        } else {
          Vector.empty
        }

        (scheduleEntryAdded +: firedEvent, ())
      }
    }
  override def fireEntry(scheduleName: String,
                         scheduleBucket: String,
                         entryId: String): Handler[ScheduleState, Seq[ScheduleEvent], Unit] =
    Handler { state =>
      state
        .findEntry(entryId)
        .map(
          entry =>
            ScheduleEntryFired(
              scheduleName,
              scheduleBucket,
              entry.id,
              entry.correlationId,
              timestamp
          )
        )
        .toVector -> (())
    }
}
