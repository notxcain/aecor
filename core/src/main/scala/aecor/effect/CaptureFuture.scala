package aecor.effect

import cats.Functor
import cats.data.{ EitherT, Kleisli }

import scala.concurrent.Future

// Greek alphabet letters & symbols (α,β,γ,δ,ε)

trait CaptureFuture[F[_]] {
  def captureFuture[A](future: => Future[A]): F[A]
}

object CaptureFuture extends CaptureFutureInstances {
  def apply[F[_]](implicit instance: CaptureFuture[F]): CaptureFuture[F] = instance
  object ops {
    implicit class FutureOps[A](self: => Future[A]) {
      def captureF[F[_]](implicit F: CaptureFuture[F]): F[A] = F.captureFuture(self)
    }
    implicit class CaptureFutureIdOps[F[_], A](val self: F[A]) extends AnyVal {
      def recapture[G[_]](implicit F: Async[F], G: CaptureFuture[G]): G[A] =
        G.captureFuture(F.unsafeRun(self))
    }
  }
}
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
