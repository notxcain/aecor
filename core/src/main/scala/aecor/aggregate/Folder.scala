package aecor.aggregate

import cats.implicits._
import cats.{ Foldable, Functor, Monad }

trait Folder[F[_], A, B] { outer =>
  def zero: B
  def reduce(b: B, a: A): F[B]
  def consume[I[_]: Foldable](f: I[A])(implicit F: Monad[F]): F[B] = f.foldM(zero)(reduce)
  def imap[C](bc: B => C, cb: C => B)(implicit F: Functor[F]): Folder[F, A, C] =
    new Folder[F, A, C] {
      override def zero: C = bc(outer.zero)
      override def reduce(b: C, a: A): F[C] = outer.reduce(cb(b), a).map(bc)
    }
}

object Folder {

  def apply[F[_], A, B](implicit instance: Folder[F, A, B]): Folder[F, A, B] = instance

  def instance[F[_], A, B](b: B)(f: (B) => (A) => F[B]): Folder[F, A, B] =
    new Folder[F, A, B] {
      override def zero: B = b
      override def reduce(b: B, a: A): F[B] = f(b)(a)
    }

  def optionInstance[F[_]: Functor, A, B](
    none: A => F[B]
  )(some: B => A => F[B]): Folder[F, A, Option[B]] =
    instance(Option.empty[B]) {
      case None => none.andThen(_.map(Some(_)))
      case Some(b) => some(b).andThen(_.map(Some(_)))
    }
}
