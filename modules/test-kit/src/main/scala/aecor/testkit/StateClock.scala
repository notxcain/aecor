package aecor.testkit

import java.time.temporal.TemporalAmount
import java.time.{ Instant, ZoneId }

import aecor.util.Clock
import cats.mtl.MonadState
import monocle.Lens

class StateClock[F[_]: MonadState[?[_], S], S](zoneId: ZoneId, S: Lens[S, Instant])
    extends Clock[F] {
  private val F = S.transformMonadState(MonadState[F, S])
  override def zone: F[ZoneId] = F.monad.pure(zoneId)
  override def instant: F[Instant] = F.get
  def tick(temporalAmount: TemporalAmount): F[Unit] =
    F.modify(_.plus(temporalAmount))
}

object StateClock {
  def apply[F[_], S](zoneId: ZoneId,
                     S: Lens[S, Instant])(implicit F0: MonadState[F, S]): StateClock[F, S] =
    new StateClock[F, S](zoneId, S)
}
