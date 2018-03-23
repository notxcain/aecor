package aecor.encoding

import shapeless.Unwrapped

private[encoding] sealed abstract class AnyValKeyDecoder[A] extends KeyDecoder[A]
private[encoding] object AnyValKeyDecoder {
  implicit def instance[A <: AnyVal, U](implicit A: Unwrapped.Aux[A, U],
                              U: KeyDecoder[U]): AnyValKeyDecoder[A] =
    new AnyValKeyDecoder[A] {
      final override def apply(key: String): Option[A] =
        U.decode(key).map(A.wrap)
    }
}
