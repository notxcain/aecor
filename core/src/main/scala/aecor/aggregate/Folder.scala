package aecor.aggregate

trait Folder[F[_], A, B] {
  def zero: B
  def step(b: B, a: A): F[B]
}

object Folder {

  def apply[F[_], A, B](implicit instance: Folder[F, A, B]): Folder[F, A, B] = instance

  sealed trait MkInstanceFor[A] {
    def apply[F[_], B](b: B)(f: B => A => F[B]): Folder[F, A, B]
  }

  def instanceFor[A]: MkInstanceFor[A] =
    new MkInstanceFor[A] {
      override def apply[F[_], B](b: B)(f: (B) => (A) => F[B]): Folder[F, A, B] =
        new Folder[F, A, B] {
          override def zero: B = b
          override def step(b: B, a: A): F[B] = f(b)(a)
        }
    }

  sealed trait MkInstance[A, B] {
    def apply[F[_]](b: B)(f: B => A => F[B]): Folder[F, A, B]
  }

  def instance[A, B]: MkInstance[A, B] = new MkInstance[A, B] {
    override def apply[F[_]](b: B)(f: (B) => (A) => F[B]): Folder[F, A, B] =
      new Folder[F, A, B] {
        override def zero: B = b
        override def step(b: B, a: A): F[B] = f(b)(a)
      }
  }

}
