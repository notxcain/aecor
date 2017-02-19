package aecor.aggregate.runtime

import scala.concurrent.{ ExecutionContext, Future }

trait Async[F[_]] {
  def run[A](fa: F[A])(executionContext: ExecutionContext): Future[A]
  def capture[A](future: => Future[A]): F[A]
}

object Async extends AsyncInstances {
  def apply[F[_]](implicit instance: Async[F]): Async[F] = instance
  object ops {
    implicit class AsyncOps[F[_], A](val self: F[A]) extends AnyVal {
      def run(implicit F: Async[F], executionContext: ExecutionContext): Future[A] =
        F.run(self)(executionContext)
    }
  }
}
sealed trait AsyncInstances {
  implicit val futureAsyncInstance: Async[Future] = new Async[Future] {
    override def run[A](fa: Future[A])(executionContext: ExecutionContext): Future[A] = fa
    override def capture[A](future: => Future[A]): Future[A] = future
  }
}
