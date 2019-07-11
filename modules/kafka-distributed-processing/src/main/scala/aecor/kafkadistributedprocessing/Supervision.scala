package aecor.kafkadistributedprocessing

import cats.effect.{ Sync, Timer }
import fs2.Stream.retry
import scala.concurrent.duration._

object Supervision {
  type Supervision[F[_]] = F[Unit] => F[Unit]
  def exponentialBackoff[F[_]: Timer: Sync](minBackoff: FiniteDuration = 2.seconds,
                                            maxBackoff: FiniteDuration = 10.seconds,
                                            randomFactor: Double = 0.2,
                                            maxAttempts: Int = Int.MaxValue): Supervision[F] = {
    def nextDelay(in: FiniteDuration): FiniteDuration =
      FiniteDuration((in.toMillis * (1 + randomFactor)).toLong, MILLISECONDS).min(maxBackoff)
    fa =>
      retry(fa, minBackoff, nextDelay, maxAttempts, Function.const(true)).compile.drain
  }
  def noop[F[_]]: Supervision[F] = identity
}
