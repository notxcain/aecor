package aecor.encoding

import java.util.UUID

abstract class KeyEncoder[A] { self =>
  def apply(a: A): String

  /**
    * Construct an instance for type `B` from an instance for type `A`.
    */
  final def contramap[B](f: B => A): KeyEncoder[B] = new KeyEncoder[B] {
    final def apply(key: B): String = self(f(key))
  }
}

final object KeyEncoder {
  @inline def apply[A](implicit A: KeyEncoder[A]): KeyEncoder[A] = A

  def instance[A](f: A => String): KeyEncoder[A] = new KeyEncoder[A] {
    def apply(key: A): String = f(key)
  }

  implicit def anyVal[A <: AnyVal](implicit A: AnyValKeyEncoder[A]): KeyEncoder[A] = A

  implicit val encodeKeyString: KeyEncoder[String] = new KeyEncoder[String] {
    final def apply(key: String): String = key
  }

  implicit val encodeKeySymbol: KeyEncoder[Symbol] = new KeyEncoder[Symbol] {
    final def apply(key: Symbol): String = key.name
  }

  implicit val encodeKeyUUID: KeyEncoder[UUID] = new KeyEncoder[UUID] {
    final def apply(key: UUID): String = key.toString
  }

  implicit val encodeKeyByte: KeyEncoder[Byte] = new KeyEncoder[Byte] {
    final def apply(key: Byte): String = java.lang.Byte.toString(key)
  }

  implicit val encodeKeyShort: KeyEncoder[Short] = new KeyEncoder[Short] {
    final def apply(key: Short): String = java.lang.Short.toString(key)
  }

  implicit val encodeKeyInt: KeyEncoder[Int] = new KeyEncoder[Int] {
    final def apply(key: Int): String = java.lang.Integer.toString(key)
  }

  implicit val encodeKeyLong: KeyEncoder[Long] = new KeyEncoder[Long] {
    final def apply(key: Long): String = java.lang.Long.toString(key)
  }
}
