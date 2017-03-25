package aecor.schedule

import java.time.{ LocalDateTime, ZonedDateTime }

import aecor.aggregate.{ Correlation, CorrelationId }
import aecor.data.Handler
import aecor.schedule.ScheduleEvent.{ ScheduleEntryAdded, ScheduleEntryFired }
import cats.Functor
import cats.implicits._

import scala.collection.immutable.Seq

object DefaultScheduleAggregate {

  def apply[F[_]: Functor](
    clock: F[ZonedDateTime]
  ): ScheduleAggregate[Handler[F, ScheduleState, Seq[ScheduleEvent], ?]] =
    new DefaultScheduleAggregate(clock)

  def correlation: Correlation[ScheduleOp] =
    c => CorrelationId.composite("-", c.scheduleName, c.scheduleBucket)

}

class DefaultScheduleAggregate[F[_]: Functor](clock: F[ZonedDateTime])
    extends ScheduleAggregate[Handler[F, ScheduleState, Seq[ScheduleEvent], ?]] {

  override def addScheduleEntry(
    scheduleName: String,
    scheduleBucket: String,
    entryId: String,
    correlationId: CorrelationId,
    dueDate: LocalDateTime
  ): Handler[F, ScheduleState, Seq[ScheduleEvent], Unit] =
    Handler { state =>
      clock.map { c =>
        val timestamp = c.toInstant
        val now = c.toLocalDateTime
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

    }
  override def fireEntry(scheduleName: String,
                         scheduleBucket: String,
                         entryId: String): Handler[F, ScheduleState, Seq[ScheduleEvent], Unit] =
    Handler { state =>
      clock.map(_.toInstant).map { timestamp =>
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
}
