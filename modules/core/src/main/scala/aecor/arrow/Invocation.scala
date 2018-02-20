package aecor.arrow

trait Invocation[M[_[_]], A] {
  def invoke[F[_]](actions: M[F]): F[A]
}
