package aecor.aggregate.runtime

trait Cached[F[_]] {
  def cache[A](f: => A): F[A]
}

object Cached {
  def apply[F[_]](implicit instance: Cached[F]): Cached[F] = instance
}
