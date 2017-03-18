package aecor.aggregate.runtime

import cats.data.{ EitherT, Kleisli }

import scala.concurrent.{ ExecutionContext, Future }
import Async.ops._

/**
  * The type class for types that can be run in async manner
  */
trait Async[F[_]] {
  def unsafeRun[A](fa: F[A]): Future[A]
}

object Async extends AsyncInstances {
  def apply[F[_]](implicit instance: Async[F]): Async[F] = instance
  object ops {
    implicit class AsyncOps[F[_], A](val self: F[A]) extends AnyVal {
      def unsafeRun(implicit F: Async[F]): Future[A] =
        F.unsafeRun(self)
    }
  }
}
sealed trait AsyncInstances {
  implicit val kleisliAsyncInstance: Async[Kleisli[Future, Unit, ?]] =
    new Async[Kleisli[Future, Unit, ?]] {
      override def unsafeRun[A](fa: Kleisli[Future, Unit, A]): Future[A] = fa.run(())
    }

  implicit def eitherTAsync[F[_]: Async, L <: Throwable](
    implicit executionContext: ExecutionContext
  ): Async[EitherT[F, L, ?]] = new Async[EitherT[F, L, ?]] {
    override def unsafeRun[A](fa: EitherT[F, L, A]): Future[A] =
      fa.value.unsafeRun.flatMap {
        case Right(a) => Future.successful(a)
        case Left(a) => Future.failed(a)
      }
  }

  implicit def fs2Task(implicit S: fs2.Strategy,
                       E: scala.concurrent.ExecutionContext): Async[fs2.Task] =
    new Async[fs2.Task] {
      override def unsafeRun[A](fa: fs2.Task[A]): Future[A] =
        fa.unsafeRunAsyncFuture()
    }

  implicit def monixTask(implicit scheduler: monix.execution.Scheduler): Async[monix.eval.Task] =
    new Async[monix.eval.Task] {
      override def unsafeRun[A](fa: monix.eval.Task[A]): Future[A] = fa.runAsync
    }
}
