package aecor.data

import cats.implicits._
import cats.{ Foldable, Functor, Monad }

final case class Folder[F[_], A, B](zero: B, reduce: (B, A) => F[B]) {
  def withZero(b: B): Folder[F, A, B] = copy(zero = b)
  def consume[I[_]: Foldable](f: I[A])(implicit F: Monad[F]): F[B] = f.foldM(zero)(reduce)
  def imap[C](bc: B => C, cb: C => B)(implicit F: Functor[F]): Folder[F, A, C] =
    Folder[F, A, C](bc(zero), (b, a) => reduce(cb(b), a).map(bc))
  def mapK[G[_]](f: F[B] => G[B]): Folder[G, A, B] = Folder(zero, (b, a) => f(reduce(b, a)))
}

object Folder {
  def curried[F[_], A, B](b: B)(reducer: (B) => (A) => F[B]): Folder[F, A, B] =
    Folder(b, (b, a) => reducer(b)(a))

  def optionInstance[F[_]: Functor, A, B](
    init: A => F[B]
  )(reduce: B => A => F[B]): Folder[F, A, Option[B]] =
    curried(Option.empty[B]) {
      case None    => init.andThen(_.map(Some(_)))
      case Some(b) => reduce(b).andThen(_.map(Some(_)))
    }
}
