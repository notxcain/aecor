package aecor.core

import aecor.core.concurrent.Deferred
import cats.data.Kleisli

import scala.concurrent.{ExecutionContext, Future}

class DeferredFOps[A](val self: Deferred[A]) extends AnyVal {
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

package object concurrent {
  type Deferred[A] = Kleisli[Future, ExecutionContext, A]
  implicit def toDeferredOps[A](a: Deferred[A]): DeferredFOps[A] = new DeferredFOps(a)
  object Deferred {
    def apply[A](f: ExecutionContext => Future[A]): Deferred[A] = Kleisli[Future, ExecutionContext, A](f)
    def value[A](a: => A): Deferred[A] = Deferred(_ => Future.successful(a))
    def failed[A](exception: Throwable): Deferred[A] = Deferred(_ => Future.failed(exception))
    def fromFuture[A](f: => Future[A]): Deferred[A] = Deferred(_ => f)
  }
}
