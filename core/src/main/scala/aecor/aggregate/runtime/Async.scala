package aecor.aggregate.runtime

import scala.concurrent.Future

/**
  * The type class for types that can be run in async manner
  */
trait Async[F[_]] {
  def unsafeRun[A](fa: F[A]): Future[A]
  def capture[A](future: => Future[A]): F[A]
}

object Async extends AsyncInstances {
  def apply[F[_]](implicit instance: Async[F]): Async[F] = instance
  object ops {
    implicit class AsyncOps[F[_], A](val self: F[A]) extends AnyVal {
      def unsafeRun(implicit F: Async[F]): Future[A] =
        F.unsafeRun(self)

      def recapture[G[_]](implicit F: Async[F], G: Async[G]): G[A] =
        G.capture(unsafeRun)
    }
    implicit class FutureOps[A](self: => Future[A]) {
      def capture[F[_]](implicit F: Async[F]): F[A] = F.capture(self)
    }
  }
}
sealed trait AsyncInstances {
  implicit val futureAsyncInstance: Async[Future] = new Async[Future] {
    override def unsafeRun[A](fa: Future[A]): Future[A] = fa
    override def capture[A](future: => Future[A]): Future[A] = future
  }

  implicit def fs2Task(implicit S: fs2.Strategy,
                       E: scala.concurrent.ExecutionContext): Async[fs2.Task] =
    new Async[fs2.Task] {
      override def capture[A](future: => Future[A]): fs2.Task[A] = fs2.Task.fromFuture(future)
      override def unsafeRun[A](fa: fs2.Task[A]): Future[A] =
        fa.unsafeRunAsyncFuture()
    }

  implicit def monixTask(implicit scheduler: monix.execution.Scheduler): Async[monix.eval.Task] =
    new Async[monix.eval.Task] {
      override def unsafeRun[A](fa: monix.eval.Task[A]): Future[A] = fa.runAsync
      override def capture[A](future: => Future[A]): monix.eval.Task[A] =
        monix.eval.Task.defer(monix.eval.Task.fromFuture(future))
    }
}
