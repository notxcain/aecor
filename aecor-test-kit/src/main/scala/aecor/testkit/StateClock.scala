package aecor.testkit

import java.time.temporal.TemporalAmount
import java.time.{ Instant, ZoneId }

import aecor.util.Clock
import cats.Applicative
import cats.data.StateT

class StateClock[F[_]: Applicative, S](zoneId: ZoneId,
                                       extract: S => Instant,
                                       update: (S, Instant) => S)
    extends Clock[StateT[F, S, ?]] {

  override def zone: StateT[F, S, ZoneId] = StateT.pure(zoneId)

  override def instant: StateT[F, S, Instant] = StateT.get[F, Instant].transformS(extract, update)

  def tick(temporalAmount: TemporalAmount): StateT[F, S, Unit] =
    StateT
      .modify[F, Instant](_.plus(temporalAmount))
      .transformS(extract, update)
}

object StateClock {
  def apply[F[_]: Applicative, S](zoneId: ZoneId,
                                  extract: S => Instant,
                                  update: (S, Instant) => S): StateClock[F, S] =
    new StateClock[F, S](zoneId, extract, update)
}
