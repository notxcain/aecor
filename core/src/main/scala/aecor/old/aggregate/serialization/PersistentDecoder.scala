package aecor.old.aggregate.serialization

import scala.util.{ Failure, Success }

trait PersistentDecoder[A] {
  def decode(repr: PersistentRepr): PersistentDecoder.Result[A]
}

object PersistentDecoder {
  def apply[A](implicit instance: PersistentDecoder[A]): PersistentDecoder[A] = instance

  def instance[A](f: PersistentRepr => Result[A]): PersistentDecoder[A] =
    new PersistentDecoder[A] {
      override def decode(repr: PersistentRepr): Result[A] = f(repr)
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
