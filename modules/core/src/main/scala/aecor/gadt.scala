package aecor

import java.nio.ByteBuffer

import aecor.arrow.Invocation
import aecor.gadt.WireProtocol.Decoder.DecodingResult
import aecor.gadt.WireProtocol.{ Decoder, Encoder }
import io.aecor.liberator.FunctorK

import scala.util.{ Failure, Success, Try }

object gadt {

  trait WireProtocol[M[_[_]]] {
    def decoder: Decoder[PairE[Invocation[M, ?], Encoder]]
    def encoder: M[WireProtocol.Encoded]
  }

  object WireProtocol {
    import boopickle.Default._
    type Encoded[A] = (ByteBuffer, Decoder[A])

    def Encoded[A](bytes: ByteBuffer)(implicit decoder: Decoder[A]): Encoded[A] = (bytes, decoder)

    trait Encoder[A] {
      def encode(a: A): ByteBuffer
    }

    object Encoder {
      def fromPickler[A: Pickler]: Encoder[A] = new Encoder[A] {
        override def encode(a: A): ByteBuffer =
          Pickle.intoBytes(a)
      }
    }

    trait Decoder[A] {
      def decode(bytes: ByteBuffer): DecodingResult[A]
    }

    object Decoder {
      final case class DecodingFailure(message: String,
                                       underlyingException: Option[Throwable] = None)
          extends RuntimeException(message, underlyingException.orNull)
      type DecodingResult[A] = Either[DecodingFailure, A]
      object DecodingResult {
        def fromTry[A](a: Try[A]): DecodingResult[A] =
          a match {
            case Failure(exception) => Left(DecodingFailure(exception.getMessage, Some(exception)))
            case Success(value)     => Right(value)
          }
      }
      def fromPickler[A: Pickler]: Decoder[A] =
        new Decoder[A] {
          override def decode(bytes: ByteBuffer): DecodingResult[A] =
            DecodingResult.fromTry(Try(Unpickle[A].fromBytes(bytes)))
        }
    }
  }

  sealed abstract class PairE[F[_], G[_]] {
    type A
    def left: F[A]
    def right: G[A]
  }

  object PairE {
    def apply[F[_], G[_], A](fa: F[A], ga: G[A]): PairE[F, G] = {
      type A0 = A
      new PairE[F, G] {
        final override type A = A0
        final override def left: F[A0] = fa
        final override def right: G[A0] = ga
      }
    }

    def unapply[F[_], G[_]](arg: PairE[F, G]): Option[(F[arg.A], G[arg.A])] =
      Some((arg.left, arg.right))
  }

}

/*

dec: Decoder (PairE (Invocation M) Encoder)
enc: M (a => (Bytes, Decoder a))

server: Bytes => Task Bytes =
  in =>
    dec(in).map {
      case (inv, renc) =>
         inv.invoke(actions).map(renc)
      }


client: M Task = enc.mapK {
  case (bs, rdec) =>
    server(bs).map(rdec)
}


EntityEvent key SeqNum e

JournalEntry o (EntityEvent key e)
 */
