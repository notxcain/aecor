package aecor.runtime.akkapersistence.serialization

import scala.util.{ Failure, Success }

trait PersistentDecoder[A] {
  def decode(repr: PersistentRepr): PersistentDecoder.DecodingResult[A]
}

object PersistentDecoder {
  def apply[A](implicit instance: PersistentDecoder[A]): PersistentDecoder[A] = instance

  def instance[A](f: PersistentRepr => DecodingResult[A]): PersistentDecoder[A] =
    new PersistentDecoder[A] {
      override def decode(repr: PersistentRepr): DecodingResult[A] = f(repr)
    }

  def fromCodec[A](codec: Codec[A]): PersistentDecoder[A] = new PersistentDecoder[A] {
    override def decode(repr: PersistentRepr): DecodingResult[A] =
      codec.decode(repr.payload, repr.manifest) match {
        case Failure(exception) => Left(DecodingFailure(exception.getMessage, Some(exception)))
        case Success(value) => Right(value)
      }
  }

  type DecodingResult[A] = Either[DecodingFailure, A]

}

final case class DecodingFailure(message: String, underlyingException: Option[Throwable] = None)
    extends RuntimeException(message, underlyingException.orNull)

object DecodingFailure {
  def fromThrowable(e: Throwable): DecodingFailure =
    DecodingFailure(e.getMessage, Some(e))
}
