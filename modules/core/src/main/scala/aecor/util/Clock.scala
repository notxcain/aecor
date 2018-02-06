package aecor.util

import java.time._

import cats.Apply

trait Clock[F[_]] {
  def zone: F[ZoneId]
  def instant: F[Instant]
  def zonedDateTime(implicit F: Apply[F]): F[ZonedDateTime] =
    F.map2(instant, zone)(ZonedDateTime.ofInstant)
  def offsetDateTime(implicit F: Apply[F]): F[OffsetDateTime] =
    F.map2(instant, zone)(OffsetDateTime.ofInstant)
  def localDateTime(implicit F: Apply[F]): F[LocalDateTime] =
    F.map2(instant, zone)(LocalDateTime.ofInstant)
}
