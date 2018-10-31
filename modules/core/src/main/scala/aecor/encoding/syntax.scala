package aecor.encoding
import cats.MonadError
import scodec.Attempt

object syntax {
  implicit final class AecorEncodingScodecAttemptOps[A](val self: Attempt[A]) extends AnyVal {
    final def lift[F[_]](implicit F: MonadError[F, Throwable]): F[A] = self match {
      case Attempt.Successful(value) => F.pure(value)
      case Attempt.Failure(cause) =>
        F.raiseError(new IllegalArgumentException(cause.messageWithContext))
    }
  }
}
