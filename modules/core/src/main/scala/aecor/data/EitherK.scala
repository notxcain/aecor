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
final case class EitherK[M[_[_]], F[_], A](value: M[EitherT[F, A, ?]]) extends AnyVal {
  def run[B](f: M[EitherT[F, A, ?]] => EitherT[F, A, B]): F[Either[A, B]] =
    f(value).value
  def unwrap(implicit M: FunctorK[M]): M[λ[B => F[Either[A, B]]]] =
    M.mapK[EitherT[F, A, ?], λ[B => F[Either[A, B]]]](value)(
      new (EitherT[F, A, ?] ~> λ[B => F[Either[A, B]]]) {
        override def apply[X](fa: EitherT[F, A, X]): F[Either[A, X]] = fa.value
      }
    )
  def mapK[G[_]](fg: F ~> G)(implicit M: FunctorK[M]): EitherK[M, G, A] =
    EitherK(M.mapK(value)(new (EitherT[F, A, ?] ~> EitherT[G, A, ?]) {
      override def apply[X](fa: EitherT[F, A, X]): EitherT[G, A, X] =
        fa.mapK(fg)
    }))
}

object EitherK {
  implicit def wireProtocol[M[_[_]]: FunctorK, F[_], A](
    implicit M: WireProtocol[M],
    aCodec: Codec[A]
  ): WireProtocol[EitherK[M, ?[_], A]] =
    new WireProtocol[EitherK[M, ?[_], A]] {

      final override val encoder: EitherK[M, Encoded, A] =
        EitherK[M, Encoded, A] {
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

      final override val decoder: Decoder[PairE[Invocation[EitherK[M, ?[_], A], ?], Encoder]] =
        M.decoder.map { p =>
          val (invocation, encoder) = (p.first, p.second)

          val eitherKInvocation =
            new Invocation[EitherK[M, ?[_], A], Either[A, p.A]] {
              override def run[G[_]](target: EitherK[M, G, A]): G[Either[A, p.A]] =
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

  implicit def functorK[M[_[_]]: FunctorK, A]: FunctorK[EitherK[M, ?[_], A]] =
    new FunctorK[EitherK[M, ?[_], A]] {
      override def mapK[F[_], G[_]](af: EitherK[M, F, A])(fk: ~>[F, G]): EitherK[M, G, A] =
        af.mapK(fk)
    }

  def wrapInvocation[M[_[_]], R, A](
    inner: Invocation[M, A]
  ): Invocation[EitherK[M, ?[_], R], Either[R, A]] =
    new Invocation[EitherK[M, ?[_], R], Either[R, A]] {
      override def run[G[_]](target: EitherK[M, G, R]): G[Either[R, A]] =
        inner.run(target.value).value
      override def toString: String = inner.toString
    }
  object syntax {
    implicit final class EitherKSynatxImpl[M[_[_]], F[_], R](val self: M[EitherT[F, R, ?]])
        extends AnyVal {
      def toEitherK: EitherK[M, F, R] = EitherK(self)
    }
  }
}
