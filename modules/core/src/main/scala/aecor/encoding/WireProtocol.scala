package aecor.encoding

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import aecor.data.PairE
import aecor.encoding.WireProtocol.Decoder.DecodingResult
import aecor.encoding.WireProtocol.{Decoder, Encoder}
import cats.implicits._
import io.aecor.liberator.{Invocation, ReifiedInvocations}

import scala.util.{Failure, Success, Try}

trait WireProtocol[M[_[_]]] extends ReifiedInvocations[M] {
  def decoder: Decoder[PairE[Invocation[M, ?], Encoder]]
  def encoder: M[WireProtocol.Encoded]
}

object WireProtocol {
  def apply[M[_[_]]](implicit M: WireProtocol[M]): WireProtocol[M] = M
  type Encoded[A] = (ByteBuffer, Decoder[A])

  trait Encoder[A] {
    def encode(a: A): ByteBuffer
  }

  object Encoder {
    def instance[A](f: A => ByteBuffer): Encoder[A] = new Encoder[A] {
      override def encode(a: A): ByteBuffer = f(a)
    }
    implicit val voidEncoder: Encoder[Nothing] = null
    implicit val stringEncoder: Encoder[String] = Encoder.instance[String](s => ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8)))
  }

  trait Decoder[A] {
    def decode(bytes: ByteBuffer): DecodingResult[A]
  }

  object Decoder {
    def instance[A](f: ByteBuffer => DecodingResult[A]): Decoder[A] = new Decoder[A] {
      override def decode(
        bytes: ByteBuffer
      ): DecodingResult[A] = f(bytes)
    }
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
    implicit val nothingDecoder: Decoder[Nothing] = new Decoder[Nothing] {
      override def decode(
        bytes: ByteBuffer
      ): DecodingResult[Nothing] = DecodingFailure("Impossible").asLeft
    }
    implicit val stringDecoder: Decoder[String] = Decoder.instance[String](b => Right(new String(b.array(), StandardCharsets.UTF_8)))
  }
}

