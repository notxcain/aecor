package aecor.encoding

import shapeless.Unwrapped

private[encoding] sealed abstract class AnyValKeyEncoder[A] extends KeyEncoder[A]
private[encoding] object AnyValKeyEncoder {
  implicit def instance[A <: AnyVal, U](implicit A: Unwrapped.Aux[A, U],
                                        U: KeyEncoder[U]): AnyValKeyEncoder[A] =
    new AnyValKeyEncoder[A] {
      override def apply(a: A) = U.apply(A.unwrap(a))
    }
}
