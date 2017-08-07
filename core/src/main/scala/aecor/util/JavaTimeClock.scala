package aecor.util

import java.time.{ Instant, ZoneId }

import aecor.effect.Capture

class JavaTimeClock[F[_]](underlying: java.time.Clock)(implicit F: Capture[F]) extends Clock[F] {
  override def zone: F[ZoneId] = F.capture(underlying.getZone)
  override def instant: F[Instant] = F.capture(underlying.instant())
}

object JavaTimeClock {
  def apply[F[_]: Capture](underlying: java.time.Clock): Clock[F] =
    new JavaTimeClock[F](underlying)
}
