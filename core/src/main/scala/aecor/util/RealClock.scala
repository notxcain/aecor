package aecor.util

import java.time.{ Instant, ZonedDateTime }

import aecor.effect.Capture

class RealClock[F[_]](underlying: java.time.Clock)(implicit F: Capture[F]) extends Clock[F] {
  override def instant: F[Instant] = F.capture(underlying.instant())
  override def zonedDateTime: F[ZonedDateTime] = F.capture(ZonedDateTime.now(underlying))
}

object RealClock {
  def apply[F[_]: Capture](underlying: java.time.Clock): Clock[F] = new RealClock[F](underlying)
}
