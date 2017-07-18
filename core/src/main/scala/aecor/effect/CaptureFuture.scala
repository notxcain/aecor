package aecor.effect

import cats.Functor
import cats.data.{ EitherT, Kleisli }
import simulacrum.typeclass

import scala.concurrent.Future
@typeclass
trait CaptureFuture[F[_]] {
  def captureFuture[A](future: => Future[A]): F[A]
}

object CaptureFuture extends CaptureFutureInstances

sealed trait CaptureFutureInstances {
  implicit def futureCaptureFutureInstance[B]: CaptureFuture[Kleisli[Future, B, ?]] =
    new CaptureFuture[Kleisli[Future, B, ?]] {
      override def captureFuture[A](future: => Future[A]): Kleisli[Future, B, A] =
        Kleisli(_ => future)
    }

  implicit def captureFutureEitherT[F[_]: CaptureFuture: Functor, B]
    : CaptureFuture[EitherT[F, B, ?]] =
    new CaptureFuture[EitherT[F, B, ?]] {
      override def captureFuture[A](future: => Future[A]): EitherT[F, B, A] =
        EitherT.right(CaptureFuture[F].captureFuture(future))
    }
}
