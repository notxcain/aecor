package aecor.core.concurrent

import scala.concurrent.{ExecutionContext, Future}

object Deferred {
  def value[A](a: A): Deferred[A] = Deferred(_ => Future.successful(a))
  def failed[A](exception: Exception): Deferred[A] = Deferred(_ => Future.failed(exception))
  def fromFuture[A](f: => Future[A]): Deferred[A] = Deferred(_ => f)
}

case class Deferred[+A](run: ExecutionContext => Future[A]) {
  def transform[B](f: ExecutionContext => Future[A] => Future[B]): Deferred[B] =
    Deferred(ec => f(ec)(run(ec)))

  def map[B](f: A => B): Deferred[B] =
    transform(ec => _.map(f)(ec))

  def collect[B](pf: PartialFunction[A, B]): Deferred[Option[B]] =
    transform(ec => _.map(pf.lift)(ec))

  def asFuture(implicit ec: ExecutionContext): Future[A] =
    run(ec)

  def flatMap[B](f: A => Deferred[B]): Deferred[B] =
    transform { implicit ec =>
      _.flatMap(x => f(x).asFuture)
    }

  def recoverWith[U >: A](pf: PartialFunction[Throwable, Deferred[U]]): Deferred[U] =
    transform { implicit ec =>
      _.recoverWith(pf.andThen(_.asFuture))
    }

  def recover[U >: A](pf: PartialFunction[Throwable, U]): Deferred[U] =
    transform { implicit ec =>
      _.recover(pf)
    }
}
