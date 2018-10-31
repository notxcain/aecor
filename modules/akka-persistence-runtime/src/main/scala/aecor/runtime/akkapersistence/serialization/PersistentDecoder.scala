package aecor.runtime.akkapersistence.serialization

import scala.util.Try

trait PersistentDecoder[A] {
  def decode(repr: PersistentRepr): PersistentDecoder.DecodingResult[A]
}

object PersistentDecoder {
  def apply[A](implicit instance: PersistentDecoder[A]): PersistentDecoder[A] = instance

  def instance[A](f: PersistentRepr => DecodingResult[A]): PersistentDecoder[A] =
    new PersistentDecoder[A] {
      override def decode(repr: PersistentRepr): DecodingResult[A] = f(repr)
    }

  type DecodingResult[A] = Either[DecodingFailure, A]
  object DecodingResult {
    def fromTry[A](value: Try[A]): DecodingResult[A] = value match {
      case scala.util.Success(a) => Right(a)
      case scala.util.Failure(e) => Left(DecodingFailure(e.getMessage, Some(e)))
    }
  }

  implicit val persistentReprInstance: PersistentDecoder[PersistentRepr] =
    new PersistentDecoder[PersistentRepr] {
      override def decode(repr: PersistentRepr): DecodingResult[PersistentRepr] = Right(repr)
    }

}

final case class DecodingFailure(message: String, underlyingException: Option[Throwable] = None)
    extends RuntimeException(message, underlyingException.orNull)

object DecodingFailure {
  def fromThrowable(e: Throwable): DecodingFailure =
    DecodingFailure(e.getMessage, Some(e))
}
