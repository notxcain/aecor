package aecor.effect.monix

import aecor.effect.{ Async, Capture, CaptureFuture }

import scala.concurrent.Future

trait MonixTaskInstances {
  implicit def aecorEffectMonixAsyncInstanceForTask(
    implicit scheduler: _root_.monix.execution.Scheduler
  ): Async[_root_.monix.eval.Task] =
    new Async[_root_.monix.eval.Task] {
      override def unsafeRun[A](fa: _root_.monix.eval.Task[A]): Future[A] = fa.runAsync
    }

  implicit def aecorEffectMonixCaptureInstanceForTask: Capture[_root_.monix.eval.Task] =
    new Capture[_root_.monix.eval.Task] {
      override def capture[A](a: => A): _root_.monix.eval.Task[A] = _root_.monix.eval.Task(a)
    }

  implicit def aecorEffectMonixCaptureFutureInstanceForTask(
    implicit scheduler: _root_.monix.execution.Scheduler
  ): CaptureFuture[_root_.monix.eval.Task] =
    new CaptureFuture[_root_.monix.eval.Task] {
      override def captureFuture[A](future: => Future[A]): _root_.monix.eval.Task[A] =
        _root_.monix.eval.Task.defer(_root_.monix.eval.Task.fromFuture(future))
    }
}
