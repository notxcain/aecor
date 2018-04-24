package aecor.data

import aecor.Has
import cats.implicits._
import cats.{ Applicative, Eval, Functor, Monad, Traverse }

import scala.util.{ Left, Right }

final case class Committable[F[_], +A](commit: F[Unit], value: A) {
  def map[B](f: A => B): Committable[F, B] = copy(value = f(value))
  def traverse[G[_], B](f: A => G[B])(implicit G: Functor[G]): G[Committable[F, B]] =
    G.map(f(value))(b => copy(value = b))
}

object Committable {
  implicit def aecorHasInstance[F[_], A, B](implicit B: Has[B, A]): Has[Committable[F, B], A] =
    B.contramap(_.value)

  implicit def catsMonadAndTraversInstance[F[_]: Applicative]
    : Monad[Committable[F, ?]] with Traverse[Committable[F, ?]] =
    new Monad[Committable[F, ?]] with Traverse[Committable[F, ?]] {
      override def traverse[G[_], A, B](
        fa: Committable[F, A]
      )(f: (A) => G[B])(implicit evidence$1: Applicative[G]): G[Committable[F, B]] =
        fa.traverse(f)

      override def flatMap[A, B](
        fa: Committable[F, A]
      )(f: (A) => Committable[F, B]): Committable[F, B] =
        f(fa.value)

      override def tailRecM[A, B](
        a: A
      )(f: (A) => Committable[F, Either[A, B]]): Committable[F, B] = {
        val c = f(a)
        c.value match {
          case Left(aa) => tailRecM(aa)(f)
          case Right(b) => c.copy(value = b)
        }
      }

      override def foldLeft[A, B](fa: Committable[F, A], b: B)(f: (B, A) => B): B =
        f(b, fa.value)

      override def foldRight[A, B](fa: Committable[F, A], lb: Eval[B])(
        f: (A, Eval[B]) => Eval[B]
      ): Eval[B] = f(fa.value, lb)

      override def pure[A](x: A): Committable[F, A] = Committable.pure(x)
    }
  def pure[F[_]: Applicative, A](a: A): Committable[F, A] = Committable(().pure[F], a)
  def unit[F[_]: Applicative]: Committable[F, Unit] = pure(())
  def collector[F[_], A, B](
    pf: PartialFunction[A, B]
  ): PartialFunction[Committable[F, A], Committable[F, B]] = {
    case c if pf.isDefinedAt(c.value) => c.map(pf)
  }
}
