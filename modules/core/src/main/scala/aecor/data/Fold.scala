package aecor.data

import cats.{ Foldable, Functor, Id, Monad, ~> }
import cats.implicits._

final case class Fold[F[_], A, X](initial: A, reduce: (A, X) => F[A]) {
  def withInitial(a: A): Fold[F, A, X] = copy(initial = a)
  def contramap[Y](f: Y => X): Fold[F, A, Y] =
    Fold(initial, (a, c) => reduce(a, f(c)))
  def runFoldable[G[_]: Foldable](gb: G[X])(implicit F: Monad[F]): F[A] =
    gb.foldM(initial)(reduce)
  def focus[B](get: A => B)(set: (A, B) => A)(implicit F: Functor[F]): Fold[F, B, X] =
    Fold(get(initial), (s, e) => reduce(set(initial, s), e).map(get))

  def expand[B](init: A => B, read: B => A, update: (B, A) => B)(
    implicit F: Functor[F]
  ): Fold[F, B, X] =
    Fold(init(initial), (current, e) => reduce(read(current), e).map(update(current, _)))

  def zip[B, Y](that: Fold[F, B, Y])(implicit F: Functor[F]): Fold[F, (A, B), Either[X, Y]] =
    zipWith(that)((_, _), identity, identity)

  def zipWith[B, Y, AB, XY](that: Fold[F, B, Y])(
    anaAB: (A, B) => AB,
    cataAB: AB => (A, B),
    cataXY: XY => Either[X, Y]
  )(implicit F: Functor[F]): Fold[F, AB, XY] =
    Fold(anaAB(initial, that.initial), { (ab, xy) =>
      val (a, b) = cataAB(ab)
      cataXY(xy) match {
        case Left(x)  => reduce(a, x).map(anaAB(_, b))
        case Right(y) => reduce(b, y).map(anaAB(a, _))
      }
    })
}
object Fold {
  def optional[F[_]: Functor, A, B](empty: B => F[A])(f: (A, B) => F[A]): Fold[F, Option[A], B] =
    Fold(initial = Option.empty, reduce = { (oa: Option[A], b: B) =>
      oa.map(f(_, b)).getOrElse(empty(b)).map(Some(_))
    })
}
