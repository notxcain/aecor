package aecor.schedule

import java.time.LocalDateTime

import io.aecor.liberator.macros.{ algebra, functorK }

@algebra
@functorK
private[aecor] trait ScheduleBucket[F[_]] {
  def addScheduleEntry(entryId: String, correlationId: String, dueDate: LocalDateTime): F[Unit]

  def fireEntry(entryId: String): F[Unit]
}

private object ScheduleBucket
