package aecor

trait Has[T, A] {
  def get(t: T): A
  def contramap[D](f: D => T): Has[D, A] = Has.instance[D](x => get(f(x)))
}

object Has extends TupleInstances {
  def apply[A, T](implicit instance: Has[T, A]): Has[T, A] = instance

  final class Builder[T] {
    @inline def apply[A](f: T => A): Has[T, A] = new Has[T, A] {
      override def get(t: T): A = f(t)
    }
  }
  def instance[T]: Builder[T] = new Builder[T]

  implicit def refl[A]: Has[A, A] = instance[A](identity)
  implicit def hasTuple[A, B, T](implicit TA: Has[T, A], TB: Has[T, B]): Has[T, (A, B)] =
    instance[T](x => (TA.get(x), TB.get(x)))

  trait HasSyntax {
    implicit def toHasSyntaxIdOps[A](a: A): HasSyntaxIdOps[A] = new HasSyntaxIdOps(a)
  }

  final class HasSyntaxIdOps[T](val a: T) extends AnyVal {
    def get[X](implicit T: Has[T, X]): X = T.get(a)
  }
  object syntax extends HasSyntax
}

trait TupleInstances {
  implicit def tuple2HasA[X, A, B](implicit AX: Has[A, X]): Has[(A, B), X] =
    Has.instance[(A, B)](x => AX.get(x._1))
  implicit def tuple2HasB[X, A, B](implicit BX: Has[B, X]): Has[(A, B), X] =
    Has.instance[(A, B)](x => BX.get(x._2))

  implicit def tuple3HasA[X, A, B, C](implicit AX: Has[A, X]): Has[(A, B, C), X] =
    Has.instance[(A, B, C)](x => AX.get(x._1))
  implicit def tuple3HasB[X, A, B, C](implicit BX: Has[B, X]): Has[(A, B, C), X] =
    Has.instance[(A, B, C)](x => BX.get(x._2))
  implicit def tuple3HasC[X, A, B, C](implicit CX: Has[C, X]): Has[(A, B, C), X] =
    Has.instance[(A, B, C)](x => CX.get(x._3))
}
