package aecor.encoding

import java.nio.ByteBuffer

import aecor.ReifiedInvocation
import aecor.arrow.Invocation
import aecor.data.PairE
import aecor.encoding.WireProtocol.Decoder.DecodingResult
import aecor.encoding.WireProtocol.{ Decoder, Encoder }

import scala.util.{ Failure, Success, Try }

trait WireProtocol[M[_[_]]] extends ReifiedInvocation[M] {
  def decoder: Decoder[PairE[Invocation[M, ?], Encoder]]
  def encoder: M[WireProtocol.Encoded]
}

object WireProtocol {
  type Encoded[A] = (ByteBuffer, Decoder[A])

  def Encoded[A](bytes: ByteBuffer)(implicit decoder: Decoder[A]): Encoded[A] = (bytes, decoder)

  trait Encoder[A] {
    def encode(a: A): ByteBuffer
  }

  object Encoder {
    def fromPickler[A: boopickle.Default.Pickler]: Encoder[A] = new Encoder[A] {
      override def encode(a: A): ByteBuffer =
        boopickle.Default.Pickle.intoBytes(a).asReadOnlyBuffer()
    }
  }

  trait Decoder[A] {
    def decode(bytes: ByteBuffer): DecodingResult[A]
  }

  object Decoder {
    final case class DecodingFailure(message: String, underlyingException: Option[Throwable] = None)
        extends RuntimeException(message, underlyingException.orNull)
    type DecodingResult[A] = Either[DecodingFailure, A]
    object DecodingResult {
      def fromTry[A](a: Try[A]): DecodingResult[A] =
        a match {
          case Failure(exception) => Left(DecodingFailure(exception.getMessage, Some(exception)))
          case Success(value)     => Right(value)
        }
    }
    def fromTry[A](f: ByteBuffer => Try[A]): Decoder[A] = new Decoder[A] {
      override def decode(bytes: ByteBuffer): DecodingResult[A] =
        f(bytes) match {
          case Failure(exception) => Left(DecodingFailure(exception.getMessage, Some(exception)))
          case Success(value)     => Right(value)
        }
    }
    def fromPickler[A: boopickle.Default.Pickler]: Decoder[A] =
      fromTry(b => Try(boopickle.Default.Unpickle[A].fromBytes(b)))

  }
}
