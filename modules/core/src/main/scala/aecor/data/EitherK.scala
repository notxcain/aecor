package aecor.data

import cats.data.EitherT

/**
  * Higher-kinded transformer for EitherT
  */
final case class EitherK[M[_[_]], F[_], A](value: M[EitherT[F, A, ?]]) {
  def run[B](f: M[EitherT[F, A, ?]] => EitherT[F, A, B]): F[Either[A, B]] =
    f(value).value
}