package aecor.data

sealed abstract class PairE[F[_], G[_]] {
  type A
  def left: F[A]
  def right: G[A]
}

object PairE {
  def apply[F[_], G[_], A](fa: F[A], ga: G[A]): PairE[F, G] = {
    type A0 = A
    new PairE[F, G] {
      final override type A = A0
      final override def left: F[A0] = fa
      final override def right: G[A0] = ga
    }
  }
}
