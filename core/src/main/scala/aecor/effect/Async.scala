package aecor.effect

import aecor.effect.Async.ops._
import cats.data.{ EitherT, Kleisli }
import cats.~>
import simulacrum.typeclass

import scala.concurrent.{ ExecutionContext, Future }

/**
  * The type class for types that can be run in async manner
  * F[A] should not execute any side effects before `unsafeRun`
  */
@typeclass
trait Async[F[_]] {
  def unsafeRun[A](fa: F[A]): concurrent.Future[A]
  final def asFunctionK: F ~> Future = Lambda[F ~> Future](unsafeRun(_))
}

object Async extends AsyncInstances
sealed trait AsyncInstances {
  implicit def aecorEffectAsyncInstanceForKleisliFuture: Async[Kleisli[Future, Unit, ?]] =
    new Async[Kleisli[Future, Unit, ?]] {
      override def unsafeRun[B](fa: Kleisli[Future, Unit, B]): Future[B] =
        fa.run(())
    }

  implicit def aecorEffectAsyncInstanceForKleisliAsyncF[F[_]: Async]: Async[Kleisli[F, Unit, ?]] =
    new Async[Kleisli[F, Unit, ?]] {
      override def unsafeRun[B](fa: Kleisli[F, Unit, B]): Future[B] =
        fa.run(()).unsafeRun
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
}
