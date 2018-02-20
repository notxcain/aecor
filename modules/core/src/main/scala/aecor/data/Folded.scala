package aecor.data

import aecor.data.Folded.{ Impossible, Next }
import cats.kernel.Eq
import cats.{ Alternative, Applicative, CoflatMap, Eval, MonadError, Now, Show, Traverse }

import scala.annotation.tailrec

sealed abstract class Folded[+A] extends Product with Serializable {
  def fold[B](impossible: => B)(next: A => B): B = this match {
    case Impossible => impossible
    case Next(a)    => next(a)
  }
  def map[B](f: A => B): Folded[B] = this match {
    case Impossible => Impossible
    case Next(a)    => Next(f(a))
  }
  def flatMap[B](f: A => Folded[B]): Folded[B] = this match {
    case Impossible => Impossible
    case Next(a)    => f(a)
  }
  def getOrElse[AA >: A](that: => AA): AA = this match {
    case Impossible => that
    case Next(a)    => a
  }
  def orElse[AA >: A](that: Folded[AA]): Folded[AA] = this match {
    case Next(_)    => this
    case Impossible => that
  }
  def isNext: Boolean = fold(false)(_ => true)
  def isImpossible: Boolean = !isNext

  def filter(f: A => Boolean): Folded[A] = this match {
    case Next(a) if f(a) => this
    case _               => Impossible
  }
  def exists(f: A => Boolean): Boolean = filter(f).isNext
  def forall(f: A => Boolean): Boolean = fold(true)(f)

  def toOption: Option[A] = fold(Option.empty[A])(Some(_))
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
  implicit val aecorDataInstancesForFolded
    : Traverse[Folded] with MonadError[Folded, Unit] with CoflatMap[Folded] with Alternative[
      Folded
    ] =
    new Traverse[Folded] with MonadError[Folded, Unit] with CoflatMap[Folded]
    with Alternative[Folded] {

      def empty[A]: Folded[A] = Impossible

      def combineK[A](x: Folded[A], y: Folded[A]): Folded[A] = x orElse y

      def pure[A](x: A): Folded[A] = Next(x)

      override def map[A, B](fa: Folded[A])(f: A => B): Folded[B] =
        fa.map(f)

      def flatMap[A, B](fa: Folded[A])(f: A => Folded[B]): Folded[B] =
        fa.flatMap(f)

      @tailrec
      def tailRecM[A, B](a: A)(f: A => Folded[Either[A, B]]): Folded[B] =
        f(a) match {
          case Impossible     => Impossible
          case Next(Left(a1)) => tailRecM(a1)(f)
          case Next(Right(b)) => Next(b)
        }

      override def map2[A, B, Z](fa: Folded[A], fb: Folded[B])(f: (A, B) => Z): Folded[Z] =
        fa.flatMap(a => fb.map(b => f(a, b)))

      override def map2Eval[A, B, Z](fa: Folded[A],
                                     fb: Eval[Folded[B]])(f: (A, B) => Z): Eval[Folded[Z]] =
        fa match {
          case Impossible => Now(Impossible)
          case Next(a)    => fb.map(_.map(f(a, _)))
        }

      def coflatMap[A, B](fa: Folded[A])(f: Folded[A] => B): Folded[B] =
        if (fa.isNext) Next(f(fa)) else Impossible

      def foldLeft[A, B](fa: Folded[A], b: B)(f: (B, A) => B): B =
        fa match {
          case Impossible => b
          case Next(a)    => f(b, a)
        }

      def foldRight[A, B](fa: Folded[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
        fa match {
          case Impossible => lb
          case Next(a)    => f(a, lb)
        }

      def raiseError[A](e: Unit): Folded[A] = Impossible

      def handleErrorWith[A](fa: Folded[A])(f: (Unit) => Folded[A]): Folded[A] = fa orElse f(())

      override def traverse[G[_]: Applicative, A, B](fa: Folded[A])(f: A => G[B]): G[Folded[B]] =
        fa match {
          case Impossible => Applicative[G].pure(Impossible)
          case Next(a)    => Applicative[G].map(f(a))(Next(_))
        }

      override def exists[A](fa: Folded[A])(p: A => Boolean): Boolean =
        fa.exists(p)

      override def forall[A](fa: Folded[A])(p: A => Boolean): Boolean =
        fa.forall(p)

      override def isEmpty[A](fa: Folded[A]): Boolean =
        fa.isImpossible
    }

  implicit def aecorDataShowForFolded[A](implicit A: Show[A]): Show[Folded[A]] =
    new Show[Folded[A]] {
      def show(fa: Folded[A]): String = fa match {
        case Next(a)    => s"Next(${A.show(a)})"
        case Impossible => "Impossible"
      }
    }

  implicit def aecorDataEqForFolded[A](implicit A: Eq[A]): Eq[Folded[A]] =
    Eq.instance {
      case (Next(l), Next(r))       => A.eqv(l, r)
      case (Impossible, Impossible) => true
      case _                        => false
    }
}
