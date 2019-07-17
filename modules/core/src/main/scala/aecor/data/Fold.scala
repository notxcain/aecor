package aecor.data

import cats.implicits._
import cats.{ Applicative, Foldable, Functor, Monad }

final case class Fold[F[_], A, X](initial: A, reduce: (A, X) => F[A]) {
  def init(a: A): Fold[F, A, X] = copy(initial = a)

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

  def and[B, AB](that: Fold[F, B, X])(implicit F: Applicative[F]): Fold[F, (A, B), X] =
    andWith(that)((_, _), identity)

  def andWith[B, AB](
    that: Fold[F, B, X]
  )(ana: (A, B) => AB, cata: AB => (A, B))(implicit F: Applicative[F]): Fold[F, AB, X] =
    Fold(ana(initial, that.initial), { (ab, x) =>
      val (a, b) = cata(ab)
      F.map2(reduce(a, x), that.reduce(b, x))(ana)
    })

  def or[B, Y](that: Fold[F, B, Y])(implicit F: Functor[F]): Fold[F, (A, B), Either[X, Y]] =
    orWith(that)((_, _), identity, identity)

  def orWith[B, Y, AB, XY](that: Fold[F, B, Y])(
    anaAB: (A, B) => AB,
    cataAB: AB => (A, B),
    cataXY: XY => Either[X, Y]
  )(implicit F: Functor[F]): Fold[F, AB, XY] =
    Fold(anaAB(initial, that.initial), { (ab, xy) =>
      val (a, b) = cataAB(ab)
      cataXY(xy) match {
        case Left(x)  => reduce(a, x).map(anaAB(_, b))
        case Right(y) => that.reduce(b, y).map(anaAB(a, _))
      }
    })
}
object Fold {
  def optional[F[_]: Functor, A, B](empty: B => F[A])(f: (A, B) => F[A]): Fold[F, Option[A], B] =
    Fold(initial = Option.empty, reduce = { (oa: Option[A], b: B) =>
      oa.map(f(_, b)).getOrElse(empty(b)).map(Some(_))
    })

  def count[F[_], A](implicit F: Applicative[F]): Fold[F, Long, A] =
    Fold(0L, (c, _) => F.pure(c + 1L))
}
