package aecor.old.aggregate

import cats.{ Foldable, Monad }
import cats.implicits._

trait Folder[F[_], A, B] {
  def zero: B
  def fold(b: B, a: A): F[B]
  def consume[I[_]: Foldable](f: I[A])(implicit F: Monad[F]): F[B] = f.foldM(zero)(fold)
}

object Folder {
  def instance[F[_], A, B](b: B)(f: (B) => (A) => F[B]): Folder[F, A, B] =
    new Folder[F, A, B] {
      override def zero: B = b
      override def fold(b: B, a: A): F[B] = f(b)(a)
    }
}
