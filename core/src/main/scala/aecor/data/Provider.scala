package aecor.data

trait Provider[I, F[_], M[_[_]]] {
  def get(i: I): M[F]
  def get[A](i: I, f: M[F] => F[A]): F[A] =
    f(get(i))

}

object Provider {
  def apply[I, F[_], M[_[_]]](f: I => M[F]): Provider[I, F, M] = new Provider[I, F, M] {
    override def get(i: I) = f(i)
  }
}
