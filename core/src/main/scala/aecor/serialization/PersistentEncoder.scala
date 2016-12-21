package aecor.serialization

import aecor.serialization.akka.{ Codec, PersistentRepr }

import scala.util.{ Failure, Success }

trait PersistentEncoder[A] {
  def encode(a: A): PersistentRepr
}

object PersistentEncoder {
  def apply[A](implicit instance: PersistentEncoder[A]): PersistentEncoder[A] = instance

  def instance[A](f: A => PersistentRepr): PersistentEncoder[A] =
    new PersistentEncoder[A] {
      override def encode(a: A) = f(a)
    }

  def fromCodec[A](codec: Codec[A]): PersistentEncoder[A] = new PersistentEncoder[A] {
    override def encode(a: A) =
      PersistentRepr(codec.manifest(a), codec.encode(a))
  }
}

trait PersistentDecoder[A] {
  def decode(repr: PersistentRepr): PersistentDecoder.Result[A]
}

object PersistentDecoder {
  def apply[A](implicit instance: PersistentDecoder[A]): PersistentDecoder[A] = instance

  def instance[A](f: PersistentRepr => Result[A]): PersistentDecoder[A] =
    new PersistentDecoder[A] {
      override def decode(repr: PersistentRepr) = f(repr)
    }

  def fromCodec[A](codec: Codec[A]): PersistentDecoder[A] = new PersistentDecoder[A] {
    override def decode(repr: PersistentRepr): Result[A] =
      codec.decode(repr.payload, repr.manifest) match {
        case Failure(exception) => Left(DecodingFailure(exception.getMessage, Some(exception)))
        case Success(value) => Right(value)
      }
  }

  type Result[A] = Either[DecodingFailure, A]

}

final case class DecodingFailure(message: String, underlyingException: Option[Throwable] = None)
    extends RuntimeException(message, underlyingException.orNull)

object DecodingFailure {
  def fromThrowable(e: Throwable): DecodingFailure =
    DecodingFailure(e.getMessage, Some(e))
}
