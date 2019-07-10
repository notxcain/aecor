package aecor.arrow

trait Invocation[M[_[_]], A] {
  def run[F[_]](mf: M[F]): F[A]
}
