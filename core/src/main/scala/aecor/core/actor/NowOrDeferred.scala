package aecor.core.actor

import aecor.core.actor.NowOrDeferred.{Deferred, Now}

import scala.concurrent.{ExecutionContext, Future}

sealed trait NowOrDeferred[+A] {
  def map[B](f: A => B): NowOrDeferred[B] = this match {
    case Now(value) => Now(f(value))
    case Deferred(run) => Deferred { implicit ec =>
      run(ec).map(f)
    }
  }

  def flatMap[B](f: A => NowOrDeferred[B]): NowOrDeferred[B] = this match {
    case Now(value) => f(value)
    case Deferred(run) => Deferred { implicit ec =>
      run(ec).flatMap { a =>
        f(a) match {
          case Now(value) => Future.successful(value)
          case Deferred(x) => x(ec)
        }
      }
    }
  }

  def asFuture(implicit ec: ExecutionContext): Future[A] = this match {
    case Now(value) => Future.successful(value)
    case Deferred(run) => run(ec)
  }
}

object NowOrDeferred {
  def now[A](value: A): NowOrDeferred[A] = Now(value)

  def deferred[A](run: ExecutionContext => Future[A]): NowOrDeferred[A] = Deferred(run)

  case class Now[+A](value: A) extends NowOrDeferred[A]

  case class Deferred[+A](run: ExecutionContext => Future[A]) extends NowOrDeferred[A]

}
