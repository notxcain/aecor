package aecor.encoding

import java.nio.ByteBuffer

import io.aecor.liberator.Invocation
import aecor.data.{EitherK, PairE}
import aecor.encoding.WireProtocol.Decoder.DecodingResult
import aecor.encoding.WireProtocol.{Decoder, Encoded, Encoder}
import cats.data.EitherT
import cats.~>
import io.aecor.liberator.ReifiedInvocations
import cats.implicits._
import io.aecor.liberator.syntax._
import scala.util.{Failure, Success, Try}

trait WireProtocol[M[_[_]]] extends ReifiedInvocations[M] {
  def decoder: Decoder[PairE[Invocation[M, ?], Encoder]]
  def encoder: M[WireProtocol.Encoded]
}

object WireProtocol {
  type Encoded[A] = (ByteBuffer, Decoder[A])

  trait Encoder[A] {
    def encode(a: A): ByteBuffer
  }

  object Encoder {
    def instance[A](f: A => ByteBuffer): Encoder[A] = new Encoder[A] {
      override def encode(a: A): ByteBuffer = f(a)
    }
    implicit val voidEncoder: Encoder[Void] = new Encoder[Void] {
      override def encode(a: Void): ByteBuffer = a.asInstanceOf[ByteBuffer]
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
    implicit val nothingDecoder: Decoder[Void] = new Decoder[Void] {
      override def decode(
        bytes: ByteBuffer
      ): DecodingResult[Void] = decode(bytes)
    }
  }
}

trait WireProtocolInstances {
  implicit def wireProtocol[M[_[_]], F[_], R](
                                               implicit M: WireProtocol[M],
                                               rdec: Decoder[R],
                                               renc: Encoder[R]
                                             ): WireProtocol[EitherK[M, ?[_], R]] =
    new WireProtocol[EitherK[M, ?[_], R]] {

      override def mapK[G[_], H[_]](mf: EitherK[M, G, R], fg: ~>[G, H]): EitherK[M, H, R] =
        EitherK(mf.value.mapK(Lambda[EitherT[G, R, ?] ~> EitherT[H, R, ?]](_.mapK(fg))))

      override def invocations: EitherK[M, Invocation[EitherK[M, ?[_], R], ?], R] =
        EitherK {
          M.mapInvocations(
            new (Invocation[M, ?] ~> EitherT[Invocation[EitherK[M, ?[_], R], ?], R, ?]) {
              override def apply[A](
                                     fa: Invocation[M, A]
                                   ): EitherT[Invocation[EitherK[M, ?[_], R], ?], R, A] =
                EitherT {
                  new Invocation[EitherK[M, ?[_], R], Either[R, A]] {
                    override def invoke[G[_]](target: EitherK[M, G, R]): G[Either[R, A]] =
                      fa.invoke(target.value).value
                  }
                }
            }
          )
        }

      override def encoder: EitherK[M, Encoded, R] =
        EitherK[M, Encoded, R] {
          M.mapInvocations(new (Invocation[M, ?] ~> EitherT[Encoded, R, ?]) {
            override def apply[A](ma: Invocation[M, A]): EitherT[Encoded, R, A] =
              EitherT[Encoded, R, A] {
                val (bytes, decM) = ma.invoke(M.encoder)
                val dec = new Decoder[Either[R, A]] {
                  override def decode(bytes: ByteBuffer): DecodingResult[Either[R, A]] = {
                    val success = bytes.get() == 1
                    if (success) {
                      decM.decode(bytes.slice()).map(_.asRight[R])
                    } else {
                      rdec.decode(bytes.slice()).map(_.asLeft[A])
                    }
                  }
                }
                (bytes, dec)
              }
          })
        }
      override def decoder
      : Decoder[PairE[Invocation[EitherK[M, ?[_], R], ?], WireProtocol.Encoder]] =
        new Decoder[PairE[Invocation[EitherK[M, ?[_], R], ?], WireProtocol.Encoder]] {
          override def decode(
                               bytes: ByteBuffer
                             ): DecodingResult[PairE[Invocation[EitherK[M, ?[_], R], ?], WireProtocol.Encoder]] =
            M.decoder.decode(bytes).map { p =>
              val (invocation, encoder) = (p.first, p.second)

              val invocationR =
                new Invocation[EitherK[M, ?[_], R], Either[R, p.A]] {
                  override def invoke[G[_]](target: EitherK[M, G, R]): G[Either[R, p.A]] =
                    invocation.invoke(target.value).value
                }

              val encoderR = Encoder.instance[Either[R, p.A]] {
                case Right(a) =>
                  val bytes = encoder.encode(a)
                  val out = ByteBuffer.allocate(1 + bytes.limit())
                  out.put(1: Byte)
                  out.put(bytes)
                  out
                case Left(r) =>
                  val bytes = renc.encode(r)
                  val out = ByteBuffer.allocate(1 + bytes.limit())
                  out.put(0: Byte)
                  out.put(bytes)
                  out
              }

              PairE(invocationR, encoderR)
            }
        }
    }
}
