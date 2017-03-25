package aecor.tests.e2e

import java.time.temporal.TemporalAmount
import java.time.{ Instant, LocalDateTime, ZoneId, ZonedDateTime }

import cats.Applicative
import cats.data.StateT

class StateClock[F[_], S](extract: S => Instant, update: (S, Instant) => S) {
  def instant(implicit F: Applicative[F]): StateT[F, S, Instant] =
    StateT.get[F, Instant].transformS(extract, update)
  def localDateTime(zoneId: ZoneId)(implicit F: Applicative[F]): StateT[F, S, LocalDateTime] =
    instant.map(LocalDateTime.ofInstant(_, zoneId))
  def zonedDateTime(zoneId: ZoneId)(implicit F: Applicative[F]): StateT[F, S, ZonedDateTime] =
    instant.map(ZonedDateTime.ofInstant(_, zoneId))

  def tick(temporalAmount: TemporalAmount)(implicit F: Applicative[F]): StateT[F, S, Unit] =
    StateT
      .modify[F, Instant](_.plus(temporalAmount))
      .transformS(extract, update)

}

object StateClock {
  def apply[F[_], S](extract: S => Instant, update: (S, Instant) => S): StateClock[F, S] =
    new StateClock(extract, update)
}
