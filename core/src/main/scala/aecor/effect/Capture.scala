package aecor.effect

import cats.data.{ EitherT, Kleisli }
import cats.{ Applicative, Functor }
import simulacrum.typeclass

import scala.concurrent.Future

@typeclass
trait Capture[F[_]] {
  def capture[A](a: => A): F[A]
  def captureFuture[A](future: => Future[A]): F[A]
}

object Capture extends CaptureInstances

sealed trait CaptureInstances {
  implicit def kleisliCapture[F[_]: Applicative: Capture, B]: Capture[Kleisli[F, B, ?]] =
    new Capture[Kleisli[F, B, ?]] {
      override def capture[A](a: => A): Kleisli[F, B, A] = Kleisli(_ => Applicative[F].pure(a))
      override def captureFuture[A](future: => Future[A]): Kleisli[F, B, A] =
        Kleisli(_ => Capture[F].captureFuture(future))
    }
  implicit def captureEitherT[F[_]: Capture: Functor, B]: Capture[EitherT[F, B, ?]] =
    new Capture[EitherT[F, B, ?]] {
      override def capture[A](a: => A): EitherT[F, B, A] = EitherT.right(Capture[F].capture(a))
      override def captureFuture[A](future: => Future[A]): EitherT[F, B, A] =
        EitherT.right(Capture[F].captureFuture(future))
    }
}
