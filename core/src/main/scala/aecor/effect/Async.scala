package aecor.effect

import cats.data.{ EitherT, Kleisli }
import cats.~>
import simulacrum.typeclass

import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.{ Failure, Success, Try }

/**
  * The type class for types that can be run in async manner
  * F[A] should not execute any side effects before `unsafeRun`
  */
@typeclass
trait Async[F[_]] {
  def unsafeRunCallback[A](fa: F[A])(f: Try[A] => Unit): Unit
  def unsafeRun[A](fa: F[A]): concurrent.Future[A] = {
    val p = Promise[A]
    unsafeRunCallback(fa) { a =>
      p.complete(a)
      ()
    }
    p.future
  }
  final def asFunctionK: F ~> Future = Lambda[F ~> Future](unsafeRun(_))
}

object Async extends AsyncInstances

sealed trait AsyncInstances {
  implicit def aecorEffectAsyncInstanceForKleisliFuture(
    implicit executionContext: ExecutionContext
  ): Async[Kleisli[Future, Unit, ?]] =
    new Async[Kleisli[Future, Unit, ?]] {

      override def unsafeRunCallback[A](fa: Kleisli[Future, Unit, A])(f: (Try[A]) => Unit): Unit =
        fa.run(()).onComplete(f)

      override def unsafeRun[B](fa: Kleisli[Future, Unit, B]): Future[B] =
        fa.run(())
    }

  implicit def aecorEffectAsyncInstanceForKleisliAsyncF[F[_]: Async]: Async[Kleisli[F, Unit, ?]] =
    new Async[Kleisli[F, Unit, ?]] {

      override def unsafeRunCallback[A](fa: Kleisli[F, Unit, A])(f: (Try[A]) => Unit): Unit =
        Async[F].unsafeRunCallback(fa.run(()))(f)

      override def unsafeRun[B](fa: Kleisli[F, Unit, B]): Future[B] =
        Async[F].unsafeRun(fa.run(()))
    }

  implicit def eitherTAsync[F[_]: Async, L <: Throwable](
    implicit executionContext: ExecutionContext
  ): Async[EitherT[F, L, ?]] = new Async[EitherT[F, L, ?]] {

    override def unsafeRunCallback[A](fa: EitherT[F, L, A])(f: (Try[A]) => Unit): Unit =
      Async[F].unsafeRunCallback(fa.value) {
        case Success(Right(a)) => f(scala.util.Success(a))
        case Success(Left(e)) => f(scala.util.Failure(e))
        case Failure(a) => f(scala.util.Failure(a))
      }

    override def unsafeRun[A](fa: EitherT[F, L, A]): Future[A] =
      Async[F].unsafeRun(fa.value).flatMap {
        case Right(a) => Future.successful(a)
        case Left(a) => Future.failed(a)
      }
  }
}
