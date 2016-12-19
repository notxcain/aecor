package aecor.serialization

import aecor.serialization.Decoder.DecodingFailure

trait Encoder[A] {
  def serialize(a: A): Array[Byte]
}

trait Decoder[A] {
  def deserialize(bytes: Array[Byte]): Either[DecodingFailure, A]
}

object Decoder {
  final case class DecodingFailure(message: String, underlyingException: Option[Exception] = None)
      extends RuntimeException(message, underlyingException.orNull)
}
