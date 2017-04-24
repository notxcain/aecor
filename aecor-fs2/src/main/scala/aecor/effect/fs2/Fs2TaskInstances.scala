package aecor.effect.fs2

import aecor.effect.{ Async, Capture, CaptureFuture }

import scala.concurrent.Future

trait Fs2TaskInstances {
  implicit def aecorEffectFs2AsyncInstanceTask(
    implicit S: _root_.fs2.Strategy,
    E: scala.concurrent.ExecutionContext
  ): Async[_root_.fs2.Task] =
    new Async[_root_.fs2.Task] {
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

  implicit def aecorEffectFs2CaptureInstanceForTask(
    implicit S: fs2.Strategy,
    E: scala.concurrent.ExecutionContext
  ): Capture[_root_.fs2.Task] =
    new Capture[_root_.fs2.Task] {
      override def capture[A](a: => A): _root_.fs2.Task[A] =
        _root_.fs2.Task.delay(a)
    }
}
