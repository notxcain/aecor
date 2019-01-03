package aecor.data

import aecor.encoding.WireProtocol
import aecor.encoding.WireProtocol.{ Encoded, Invocation }
import cats.data.EitherT
import cats.~>
import cats.implicits._
import cats.tagless.FunctorK
import scodec.{ Codec, Decoder, Encoder }
import scodec.codecs._
import cats.tagless.syntax.functorK._

/**
  * Higher-kinded transformer for EitherT
  */
final case class EitherK[M[_[_]], A, F[_]](value: M[EitherT[F, A, ?]]) extends AnyVal {
  def unwrap(implicit M: FunctorK[M]): M[λ[α => F[Either[A, α]]]] =
    value.mapK(new (EitherT[F, A, ?] ~> λ[α => F[Either[A, α]]]) {
      override def apply[X](fa: EitherT[F, A, X]): F[Either[A, X]] = fa.value
    })

  def mapK[G[_]](fg: F ~> G)(implicit M: FunctorK[M]): EitherK[M, A, G] =
    EitherK(M.mapK(value)(new (EitherT[F, A, ?] ~> EitherT[G, A, ?]) {
      override def apply[X](fa: EitherT[F, A, X]): EitherT[G, A, X] =
        fa.mapK(fg)
    }))
}

object EitherK {
  implicit def wireProtocol[M[_[_]]: FunctorK, A](
    implicit M: WireProtocol[M],
    aCodec: Codec[A]
  ): WireProtocol[EitherK[M, A, ?[_]]] =
    new WireProtocol[EitherK[M, A, ?[_]]] {

      final override val encoder: EitherK[M, A, Encoded] =
        EitherK[M, A, Encoded] {
          M.encoder.mapK(new (Encoded ~> EitherT[Encoded, A, ?]) {
            override def apply[B](ma: Encoded[B]): EitherT[Encoded, A, B] =
              EitherT[Encoded, A, B] {
                val (bytes, resultDecoder) = ma
                val dec = bool.flatMap {
                  case true  => resultDecoder.map(_.asRight[A])
                  case false => aCodec.map(_.asLeft[B])
                }
                (bytes, dec)
              }
          })
        }

      final override val decoder: Decoder[PairE[Invocation[EitherK[M, A, ?[_]], ?], Encoder]] =
        M.decoder.map { p =>
          val (invocation, encoder) = (p.first, p.second)

          val eitherKInvocation =
            new Invocation[EitherK[M, A, ?[_]], Either[A, p.A]] {
              override def run[G[_]](target: EitherK[M, A, G]): G[Either[A, p.A]] =
                invocation.run(target.value).value
              override def toString: String = invocation.toString
            }

          val eitherEncoder = Encoder { m: Either[A, p.A] =>
            m match {
              case Right(a) =>
                Encoder.encodeBoth(bool, encoder)(true, a)
              case Left(r) =>
                Encoder.encodeBoth(bool, aCodec)(false, r)
            }
          }

          PairE(eitherKInvocation, eitherEncoder)
        }
    }

  implicit def functorK[M[_[_]]: FunctorK, A]: FunctorK[EitherK[M, A, ?[_]]] =
    new FunctorK[EitherK[M, A, ?[_]]] {
      override def mapK[F[_], G[_]](af: EitherK[M, A, F])(fk: ~>[F, G]): EitherK[M, A, G] =
        af.mapK(fk)
    }

  object syntax {
    implicit final class EitherKSyntaxImpl[M[_[_]], F[_], A](val self: M[EitherT[F, A, ?]])
        extends AnyVal {
      def toEitherK: EitherK[M, A, F] = EitherK(self)
    }
  }
}
