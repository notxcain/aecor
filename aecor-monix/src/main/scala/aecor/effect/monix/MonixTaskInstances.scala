package aecor.effect.monix

import aecor.effect.Capture

import scala.concurrent.Future

trait MonixTaskInstances {
  implicit def aecorEffectMonixCaptureInstanceForTask: Capture[_root_.monix.eval.Task] =
    new Capture[_root_.monix.eval.Task] {
      override def capture[A](a: => A): _root_.monix.eval.Task[A] = _root_.monix.eval.Task(a)
      override def captureFuture[A](future: => Future[A]): _root_.monix.eval.Task[A] =
        _root_.monix.eval.Task.deferFuture(future)
    }

}
