package aecor.effect

import aecor.effect.Async.ops._
import cats.data.{ EitherT, Kleisli }
import cats.{ Monoid, ~> }
import simulacrum.typeclass

import scala.concurrent.{ ExecutionContext, Future }

/**
  * The type class for types that can be run in async manner
  */
@typeclass
trait Async[F[_]] {
  def unsafeRun[A](fa: F[A]): concurrent.Future[A]
  final def asFunctionK: F ~> Future = Lambda[F ~> Future](unsafeRun(_))
}

object Async extends AsyncInstances
sealed trait AsyncInstances {
  implicit def aecorEffectAsyncInstanceForKleisliFuture[A: Monoid]: Async[Kleisli[Future, A, ?]] =
    new Async[Kleisli[Future, A, ?]] {
      override def unsafeRun[B](fa: Kleisli[Future, A, B]): Future[B] = fa.run(Monoid[A].empty)
    }

  implicit def aecorEffectAsyncInstanceForKleisliAsyncF[F[_]: Async, A: Monoid]
    : Async[Kleisli[F, A, ?]] =
    new Async[Kleisli[F, A, ?]] {
      override def unsafeRun[B](fa: Kleisli[F, A, B]): Future[B] =
        fa.run(Monoid[A].empty).unsafeRun
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
