package aecor

trait Has[A, T] {
  def get(t: T): A
}

object Has extends TupleInstances {
  def apply[A, T](implicit instance: Has[A, T]): Has[A, T] = instance

  final class Builder[T] {
    @inline def apply[A](f: T => A): Has[A, T] = new Has[A, T] {
      override def get(t: T): A = f(t)
    }
  }
  def instance[T]: Builder[T] = new Builder[T]

  implicit def refl[A]: Has[A, A] = instance[A](identity)
  implicit def hasTuple[A, B, T](implicit TA: Has[A, T], TB: Has[B, T]): Has[(A, B), T] =
    instance[T](x => (TA.get(x), TB.get(x)))

  trait HasSyntax {
    implicit def toHasSyntaxIdOps[A](a: A): HasSyntaxIdOps[A] = new HasSyntaxIdOps(a)
  }

  final class HasSyntaxIdOps[T](val a: T) extends AnyVal {
    def get[X](implicit T: Has[X, T]): X = T.get(a)
  }
  object syntax extends HasSyntax
}

trait TupleInstances {
  implicit def tuple2HasA[X, A, B](implicit AX: Has[X, A]): Has[X, (A, B)] =
    Has.instance[(A, B)](x => AX.get(x._1))
  implicit def tuple2HasB[X, A, B](implicit BX: Has[X, B]): Has[X, (A, B)] =
    Has.instance[(A, B)](x => BX.get(x._2))

  implicit def tuple3HasA[X, A, B, C](implicit AX: Has[X, A]): Has[X, (A, B, C)] =
    Has.instance[(A, B, C)](x => AX.get(x._1))
  implicit def tuple3HasB[X, A, B, C](implicit BX: Has[X, B]): Has[X, (A, B, C)] =
    Has.instance[(A, B, C)](x => BX.get(x._2))
  implicit def tuple3HasC[X, A, B, C](implicit CX: Has[X, C]): Has[X, (A, B, C)] =
    Has.instance[(A, B, C)](x => CX.get(x._3))
}
