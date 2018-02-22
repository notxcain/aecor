package aecor

import cats.~>

sealed abstract class IsK[F[_], G[_]] {
  def substitute[A](fa: F[A]): G[A]
  final def asFunctionK: F ~> G = new (F ~> G) {
    final override def apply[A](fa: F[A]): G[A] = substitute(fa)
  }
}

object IsK {
  implicit def refl[F[_]]: IsK[F, F] = new IsK[F, F] {
    final override def substitute[A](fa: F[A]): F[A] = fa
  }
}
