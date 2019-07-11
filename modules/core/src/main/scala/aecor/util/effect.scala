package aecor.util

import java.util.concurrent.Executor

import cats.effect.{ Async, ContextShift, Effect, IO }

import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.{ Failure, Success }

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

  private val immediate = ExecutionContext.fromExecutor(new Executor {
    override def execute(command: Runnable): Unit = command.run()
  })

  implicit final class AecorLiftIOOps[F[_]](val self: Async[F]) extends AnyVal {
    def fromFuture[A](future: => Future[A])(implicit contextShift: ContextShift[F]): F[A] =
      self.guarantee(
        self
          .suspend {
            future.value match {
              case Some(result) =>
                result match {
                  case Success(a) => self.pure(a)
                  case Failure(e) => self.raiseError[A](e)
                }
              case _ =>
                self.async[A] { cb =>
                  future.onComplete(
                    r =>
                      cb(r match {
                        case Success(a) => Right(a)
                        case Failure(e) => Left(e)
                      })
                  )(immediate)
                }
            }
          }
      )(contextShift.shift)
  }
}
