package aecor.util

import cats.effect.IO
import cats.effect.std.Dispatcher

object effect {
  implicit final class AecorLiftOps[F[_], A](val fa: F[A]) extends AnyVal {
    @inline def unsafeToIO(dispatcher: Dispatcher[F]): IO[A] =
      IO.fromFuture(IO(dispatcher.unsafeToFuture(fa)))
  }
}
