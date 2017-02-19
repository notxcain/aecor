package aecor.data

import aecor.data.Folded.{ Impossible, Next }
import cats.Monad

sealed abstract class Folded[+A] {
  def isNext: Boolean = this match {
    case Next(_) => true
    case Impossible => false
  }
  def fold[B](impossible: => B, next: A => B): B = this match {
    case Impossible => impossible
    case Next(a) => next(a)
  }
  def map[B](f: A => B): Folded[B] = this match {
    case Impossible => Impossible
    case Next(a) => Next(f(a))
  }
  def flatMap[B](f: A => Folded[B]): Folded[B] = this match {
    case Impossible => Impossible
    case Next(a) => f(a)
  }
  def getOrElse[AA >: A](that: => AA): AA = this match {
    case Impossible => that
    case Next(a) => a
  }
}
object Folded extends FoldedInstances {
  final case object Impossible extends Folded[Nothing]
  final case class Next[+A](a: A) extends Folded[A]
  def impossible[A]: Folded[A] = Impossible
  def next[A](a: A): Folded[A] = Next(a)
  def collectNext[A]: PartialFunction[Folded[A], Next[A]] = {
    case next @ Next(_) => next
  }
  object syntax {
    implicit class FoldedIdOps[A](val a: A) extends AnyVal {
      def next: Folded[A] = Folded.next(a)
    }
    def impossible[A]: Folded[A] = Folded.impossible
  }
}

trait FoldedInstances {
  implicit val foldedMonad: Monad[Folded] = new Monad[Folded] {
    override def map[A, B](fa: Folded[A])(f: (A) => B): Folded[B] = fa.map(f)

    override def tailRecM[A, B](a: A)(f: (A) => Folded[Either[A, B]]): Folded[B] =
      f(a).flatMap {
        case Left(aa) => tailRecM(aa)(f)
        case Right(b) => pure(b)
      }

    override def flatMap[A, B](fa: Folded[A])(f: (A) => Folded[B]): Folded[B] =
      fa.flatMap(f)

    override def pure[A](x: A): Folded[A] = Next(x)
  }
}
