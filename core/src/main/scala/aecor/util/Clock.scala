package aecor.util

import java.time.{ Instant, ZonedDateTime }

trait Clock[F[_]] {
  def instant: F[Instant]
  def zonedDateTime: F[ZonedDateTime]
}
