package aecor.schedule

import java.time.LocalDateTime

import aecor.aggregate._
import aecor.schedule.ScheduleOp.{ AddScheduleEntry, FireEntry }
import cats.~>

sealed abstract class ScheduleOp[A] extends Product with Serializable {
  def scheduleName: String
  def scheduleBucket: String
}

object ScheduleOp {
  final case class AddScheduleEntry(scheduleName: String,
                                    scheduleBucket: String,
                                    entryId: String,
                                    correlationId: CorrelationId,
                                    dueDate: LocalDateTime)
      extends ScheduleOp[Unit]

  final case class FireEntry(scheduleName: String, scheduleBucket: String, entryId: String)
      extends ScheduleOp[Unit]
}

private[aecor] trait ScheduleAggregate[F[_]] {
  def addScheduleEntry(scheduleName: String,
                       scheduleBucket: String,
                       entryId: String,
                       correlationId: CorrelationId,
                       dueDate: LocalDateTime): F[Unit]

  def fireEntry(scheduleName: String, scheduleBucket: String, entryId: String): F[Unit]

  final def asFunctionK: ScheduleOp ~> F =
    λ[ScheduleOp ~> F] {
      case AddScheduleEntry(scheduleName, scheduleBucket, entryId, correlationId, dueDate) =>
        addScheduleEntry(scheduleName, scheduleBucket, entryId, correlationId, dueDate)
      case FireEntry(scheduleName, scheduleBucket, entryId) =>
        fireEntry(scheduleName, scheduleBucket, entryId)
    }
}

private[aecor] object ScheduleAggregate {
  def fromFunctionK[F[_]](f: ScheduleOp ~> F): ScheduleAggregate[F] =
    new ScheduleAggregate[F] {
      override def addScheduleEntry(scheduleName: String,
                                    scheduleBucket: String,
                                    entryId: String,
                                    correlationId: CorrelationId,
                                    dueDate: LocalDateTime): F[Unit] =
        f(AddScheduleEntry(scheduleName, scheduleBucket, entryId, correlationId, dueDate))

      override def fireEntry(scheduleName: String,
                             scheduleBucket: String,
                             entryId: String): F[Unit] =
        f(FireEntry(scheduleName, scheduleBucket, entryId))
    }
}
