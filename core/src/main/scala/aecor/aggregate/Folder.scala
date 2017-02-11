package aecor.aggregate

trait Folder[F[_], A, B] {
  def zero: B
  def fold(b: B, a: A): F[B]
}

object Folder {
  def instance[F[_], A, B](b: B)(f: (B) => (A) => F[B]): Folder[F, A, B] =
    new Folder[F, A, B] {
      override def zero: B = b
      override def fold(b: B, a: A): F[B] = f(b)(a)
    }

}
