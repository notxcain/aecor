package aecor.streaming

import cats.{ Applicative, Eval, Functor, Monad, Traverse }

import scala.concurrent.Future
import scala.util.{ Left, Right }

final case class Committable[+A](commit: () => Future[Unit], value: A) {
  def map[B](f: A => B): Committable[B] = copy(value = f(value))
  def traverse[G[_], B](f: A => G[B])(implicit G: Functor[G]): G[Committable[B]] =
    G.map(f(value))(b => copy(value = b))
  def foldLeft[B](b: B)(f: (B, A) => B): B = f(b, value)
}

object Committable {
  implicit def catsMonadAndTraversInstance: Monad[Committable] with Traverse[Committable] =
    new Monad[Committable] with Traverse[Committable] {

      override def flatMap[A, B](fa: Committable[A])(f: (A) => Committable[B]): Committable[B] =
        f(fa.value)

      override def tailRecM[A, B](a: A)(f: (A) => Committable[Either[A, B]]): Committable[B] = {
        val c = f(a)
        c.value match {
          case Left(aa) => tailRecM(aa)(f)
          case Right(b) => c.copy(value = b)
        }
      }

      override def pure[A](x: A): Committable[A] = Committable(() => Future.successful(()), x)

      override def traverse[G[_], A, B](
        fa: Committable[A]
      )(f: (A) => G[B])(implicit G: Applicative[G]): G[Committable[B]] =
        fa.traverse(f)

      override def foldLeft[A, B](fa: Committable[A], b: B)(f: (B, A) => B): B =
        fa.foldLeft(b)(f)

      override def foldRight[A, B](fa: Committable[A],
                                   lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
        f(fa.value, lb)
    }
  implicit def commitInstance[Offset]: Commit[Committable[Offset]] =
    new Commit[Committable[Offset]] {
      override def commit(a: Committable[Offset]): Future[Unit] = a.commit()
    }

  def collector[A, B](pf: PartialFunction[A, B]): PartialFunction[Committable[A], Committable[B]] = {
    case c if pf.isDefinedAt(c.value) => c.map(pf)
  }
}

trait Commit[A] {
  def commit(a: A): Future[Unit]
}
