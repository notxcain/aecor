package aecor.util

import cats.effect.{ Effect, IO, LiftIO }

import scala.concurrent.{ Future, Promise }

object effect {
  implicit final class AecorEffectOps[F[_], A](val self: F[A]) extends AnyVal {
    @inline final def unsafeToFuture()(implicit F: Effect[F]): Future[A] = {
      val p = Promise[A]
      F.runAsync(self) {
          case Right(a) => IO { p.success(a); () }
          case Left(e)  => IO { p.failure(e); () }
        }
        .unsafeRunSync()
      p.future
    }

  }

  implicit final class AecorLiftIOOps[F[_]](val self: LiftIO[F]) extends AnyVal {
    def fromFuture[A](future: => Future[A]): F[A] =
      IO.fromFuture(IO(future)).to(self)
  }
}
