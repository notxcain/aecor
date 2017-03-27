package aecor.effect

import aecor.effect.Async.ops._
import cats.data.{ EitherT, Kleisli }
import cats.~>
import simulacrum.typeclass

import scala.concurrent
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
}
