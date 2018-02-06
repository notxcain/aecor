package aecor.schedule

import java.time.LocalDateTime

import aecor.schedule.ScheduleOp.{ AddScheduleEntry, FireEntry }
import cats.~>

sealed abstract class ScheduleOp[A] extends Product with Serializable

object ScheduleOp {
  final case class AddScheduleEntry(entryId: String, correlationId: String, dueDate: LocalDateTime)
      extends ScheduleOp[Unit]

  final case class FireEntry(entryId: String) extends ScheduleOp[Unit]
}

private[aecor] trait ScheduleBucket[F[_]] {
  def addScheduleEntry(entryId: String, correlationId: String, dueDate: LocalDateTime): F[Unit]

  def fireEntry(entryId: String): F[Unit]

  final def asFunctionK: ScheduleOp ~> F =
    λ[ScheduleOp ~> F] {
      case AddScheduleEntry(entryId, correlationId, dueDate) =>
        addScheduleEntry(entryId, correlationId, dueDate)
      case FireEntry(entryId) =>
        fireEntry(entryId)
    }
}

private[aecor] object ScheduleBucket {
  def fromFunctionK[F[_]](f: ScheduleOp ~> F): ScheduleBucket[F] =
    new ScheduleBucket[F] {
      override def addScheduleEntry(entryId: String,
                                    correlationId: String,
                                    dueDate: LocalDateTime): F[Unit] =
        f(AddScheduleEntry(entryId, correlationId, dueDate))

      override def fireEntry(entryId: String): F[Unit] =
        f(FireEntry(entryId))
    }
}
