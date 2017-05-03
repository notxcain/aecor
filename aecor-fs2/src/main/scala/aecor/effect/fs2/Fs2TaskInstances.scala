package aecor.effect.fs2

import aecor.effect.{ Async, Capture, CaptureFuture }

import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

trait Fs2TaskInstances {
  implicit def aecorEffectFs2AsyncInstanceTask: Async[_root_.fs2.Task] =
    new Async[_root_.fs2.Task] {

      override def unsafeRunCallback[A](fa: _root_.fs2.Task[A])(f: (Try[A]) => Unit): Unit =
        fa.unsafeRunAsync {
          case Right(a) => f(Success(a))
          case Left(e) => f(Failure(e))
        }

      override def unsafeRun[A](fa: _root_.fs2.Task[A]): Future[A] =
        fa.unsafeRunAsyncFuture()
    }

  implicit def aecorEffectFs2CaptureFutureInstanceForTask(
    implicit S: fs2.Strategy,
    E: scala.concurrent.ExecutionContext
  ): CaptureFuture[_root_.fs2.Task] =
    new CaptureFuture[_root_.fs2.Task] {
      override def captureFuture[A](future: => Future[A]): _root_.fs2.Task[A] =
        _root_.fs2.Task.fromFuture(future)
    }

  implicit def aecorEffectFs2CaptureInstanceForTask: Capture[_root_.fs2.Task] =
    new Capture[_root_.fs2.Task] {
      override def capture[A](a: => A): _root_.fs2.Task[A] =
        _root_.fs2.Task.delay(a)
    }
}
