package aecor.effect.fs2

import aecor.effect.{ Async, Capture }

import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

trait Fs2TaskInstances {
  implicit def aecorEffectFs2CaptureInstanceForTask(
    implicit S: fs2.Strategy,
    E: scala.concurrent.ExecutionContext
  ): Capture[_root_.fs2.Task] =
    new Capture[_root_.fs2.Task] {
      override def captureFuture[A](future: => Future[A]): _root_.fs2.Task[A] =
        _root_.fs2.Task.fromFuture(future)

      override def capture[A](a: => A) = _root_.fs2.Task.delay(a)
    }
}
