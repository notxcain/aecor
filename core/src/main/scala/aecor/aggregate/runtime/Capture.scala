package aecor.aggregate.runtime

import cats.{ Applicative, Functor }
import cats.data.{ EitherT, Kleisli }
import monix.eval.Task

trait Capture[F[_]] {
  def capture[A](a: => A): F[A]
}

object Capture {
  def apply[F[_]](implicit instance: Capture[F]): Capture[F] = instance
  implicit def kleisliCapture[F[_]: Applicative, B]: Capture[Kleisli[F, B, ?]] =
    new Capture[Kleisli[F, B, ?]] {
      override def capture[A](a: => A): Kleisli[F, B, A] = Kleisli(_ => Applicative[F].pure(a))
    }
  implicit def captureEitherT[F[_]: Capture: Functor, B]: Capture[EitherT[F, B, ?]] =
    new Capture[EitherT[F, B, ?]] {
      override def capture[A](a: => A): EitherT[F, B, A] = EitherT.right(Capture[F].capture(a))
    }

  implicit def captureMonixTask: Capture[monix.eval.Task] = new Capture[Task] {
    override def capture[A](a: => A): Task[A] = Task(a)
  }
}
