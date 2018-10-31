package aecor.data

/**
  * Existential pair of type constructors.
  */

sealed abstract class PairE[F[_], G[_]] {
  type A
  def first: F[A]
  def second: G[A]
}

object PairE {
  def apply[F[_], G[_], A](fa: F[A], ga: G[A]): PairE[F, G] = {
    type A0 = A
    new PairE[F, G] {
      final override type A = A0
      final override def first: F[A0] = fa
      final override def second: G[A0] = ga
      override def toString: String = s"Pair($first, $second)"
    }
  }
}
