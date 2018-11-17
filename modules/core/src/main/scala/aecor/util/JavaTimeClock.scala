package aecor.util

import java.time.{ Instant, ZoneId }

import cats.effect.Sync

class JavaTimeClock[F[_]](underlying: java.time.Clock)(implicit F: Sync[F]) extends Clock[F] {
  override def zone: F[ZoneId] = F.delay(underlying.getZone)
  override def instant: F[Instant] = F.delay(underlying.instant())
}

object JavaTimeClock {
  def apply[F[_]: Sync](underlying: java.time.Clock): Clock[F] =
    new JavaTimeClock[F](underlying)
  def systemDefault[F[_]: Sync]: Clock[F] = apply(java.time.Clock.systemDefaultZone())
  def systemUTC[F[_]: Sync]: Clock[F] = apply(java.time.Clock.systemUTC())
}
