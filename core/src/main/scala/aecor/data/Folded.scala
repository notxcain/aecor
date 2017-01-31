package aecor.data

import aecor.data.Folded.{ Impossible, Next }

sealed abstract class Folded[+A] {
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
object Folded {
  private final case object Impossible extends Folded[Nothing]
  private final case class Next[+A](a: A) extends Folded[A]
  def impossible[A]: Folded[A] = Impossible
  def next[A](a: A): Folded[A] = Next(a)
  object syntax {
    implicit class FoldedIdOps[A](val a: A) extends AnyVal {
      def next: Folded[A] = Folded.next(a)
    }
    def impossible[A]: Folded[A] = Folded.impossible
  }
}
