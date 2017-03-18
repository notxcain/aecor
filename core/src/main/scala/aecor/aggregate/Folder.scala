package aecor.aggregate

import cats.implicits._
import cats.{ Foldable, Monad }

trait Folder[F[_], A, B] {
  def zero: B
  def step(b: B, a: A): F[B]
  def consume[I[_]: Foldable](f: I[A])(implicit F: Monad[F]): F[B] = f.foldM(zero)(step)
}

object Folder {

  def apply[F[_], A, B](implicit instance: Folder[F, A, B]): Folder[F, A, B] = instance

  def instance[F[_], A, B](b: B)(f: (B) => (A) => F[B]): Folder[F, A, B] =
    new Folder[F, A, B] {
      override def zero: B = b
      override def step(b: B, a: A): F[B] = f(b)(a)
    }
}
