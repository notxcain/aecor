package aecor.core.concurrent

import cats.data.Kleisli

import scala.concurrent.{ExecutionContext, Future}

object Deferred {
  def apply[A](f: ExecutionContext => Future[A]): Deferred[A] = Kleisli(f)
  def value[A](a: => A): Deferred[A] = Deferred(_ => Future.successful(a))
  def failed[A](exception: Throwable): Deferred[A] = Deferred(_ => Future.failed(exception))
  def fromFuture[A](f: => Future[A]): Deferred[A] = Deferred(_ => f)
}

class DeferredOps[A](val self: Deferred[A]) extends AnyVal {
  def asFuture(implicit ec: ExecutionContext): Future[A] = self(ec)
  def transform[B](f: ExecutionContext => Future[A] => Future[B]): Deferred[B] =
    Deferred(ec => f(ec)(self.run(ec)))
  def collect[B](pf: PartialFunction[A, B]): Deferred[Option[B]] =
    transform(ec => _.map(pf.lift)(ec))
  def recoverWith[U >: A](pf: PartialFunction[Throwable, Deferred[U]]): Deferred[U] =
    transform { implicit ec =>
      _.recoverWith(pf.andThen(_.run(ec)))
    }

  def recover[U >: A](pf: PartialFunction[Throwable, U]): Deferred[U] =
    transform { implicit ec =>
      _.recover(pf)
    }
}

