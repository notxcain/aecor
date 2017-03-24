package aecor.aggregate.runtime

import cats.Functor
import cats.data.{ EitherT, Kleisli }

import scala.concurrent.Future

// Greek alphabet letters & symbols (α,β,γ,δ,ε)

trait CaptureFuture[F[_]] {
  def captureF[A](future: => Future[A]): F[A]
}

object CaptureFuture extends CaptureFutureInstances {
  def apply[F[_]](implicit instance: CaptureFuture[F]): CaptureFuture[F] = instance
  object ops {
    implicit class FutureOps[A](self: => Future[A]) {
      def captureF[F[_]](implicit F: CaptureFuture[F]): F[A] = F.captureF(self)
    }
    implicit class CaptureFutureIdOps[F[_], A](val self: F[A]) extends AnyVal {
      def recapture[G[_]](implicit F: Async[F], G: CaptureFuture[G]): G[A] =
        G.captureF(F.unsafeRun(self))
    }
  }
}
sealed trait CaptureFutureInstances {
  implicit def futureCaptureFutureInstance[B]: CaptureFuture[Kleisli[Future, B, ?]] =
    new CaptureFuture[Kleisli[Future, B, ?]] {
      override def captureF[A](future: => Future[A]): Kleisli[Future, B, A] =
        Kleisli(_ => future)
    }

  implicit def captureFutureEitherT[F[_]: CaptureFuture: Functor, B]
    : CaptureFuture[EitherT[F, B, ?]] =
    new CaptureFuture[EitherT[F, B, ?]] {
      override def captureF[A](future: => Future[A]): EitherT[F, B, A] =
        EitherT.right(CaptureFuture[F].captureF(future))
    }

  implicit def fs2Task(implicit S: fs2.Strategy,
                       E: scala.concurrent.ExecutionContext): CaptureFuture[fs2.Task] =
    new CaptureFuture[fs2.Task] {
      override def captureF[A](future: => Future[A]): fs2.Task[A] = fs2.Task.fromFuture(future)
    }

  implicit def monixTask(
    implicit scheduler: monix.execution.Scheduler
  ): CaptureFuture[monix.eval.Task] =
    new CaptureFuture[monix.eval.Task] {
      override def captureF[A](future: => Future[A]): monix.eval.Task[A] =
        monix.eval.Task.defer(monix.eval.Task.fromFuture(future))
    }
}
