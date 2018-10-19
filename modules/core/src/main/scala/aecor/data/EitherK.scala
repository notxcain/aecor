package aecor.data

import aecor.encoding.WireProtocol
import aecor.encoding.WireProtocol.Encoded
import cats.data.EitherT
import cats.~>
import io.aecor.liberator.{ FunctorK, Invocation, ReifiedInvocations }
import cats.implicits._
import scodec.{ Codec, Decoder, Encoder }
import scodec.codecs._
import io.aecor.liberator.syntax._

/**
  * Higher-kinded transformer for EitherT
  */
final case class EitherK[M[_[_]], F[_], A](value: M[EitherT[F, A, ?]]) {
  def run[B](f: M[EitherT[F, A, ?]] => EitherT[F, A, B]): F[Either[A, B]] =
    f(value).value
  def unwrap(implicit M: FunctorK[M]): M[λ[B => F[Either[A, B]]]] =
    M.mapK[EitherT[F, A, ?], λ[B => F[Either[A, B]]]](
      value,
      new (EitherT[F, A, ?] ~> λ[B => F[Either[A, B]]]) {
        override def apply[X](fa: EitherT[F, A, X]): F[Either[A, X]] = fa.value
      }
    )
  def mapK[G[_]](fg: F ~> G)(implicit M: FunctorK[M]): EitherK[M, G, A] =
    EitherK(M.mapK(value, new (EitherT[F, A, ?] ~> EitherT[G, A, ?]) {
      override def apply[X](fa: EitherT[F, A, X]): EitherT[G, A, X] =
        fa.mapK(fg)
    }))
}

object EitherK {
  implicit def reifiedInvocations[M[_[_]], F[_], R](
    implicit M: ReifiedInvocations[M]
  ): ReifiedInvocations[EitherK[M, ?[_], R]] = new ReifiedInvocations[EitherK[M, ?[_], R]] {
    final override def mapK[G[_], H[_]](mf: EitherK[M, G, R], fg: ~>[G, H]): EitherK[M, H, R] =
      mf.mapK(fg)

    final override def invocations: EitherK[M, Invocation[EitherK[M, ?[_], R], ?], R] =
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
                  override def toString: String = fa.toString
                }
              }
          }
        )
      }
  }
  implicit def wireProtocol[M[_[_]]: FunctorK, F[_], R](
    implicit M: WireProtocol[M],
    rejectionCodec: Codec[R]
  ): WireProtocol[EitherK[M, ?[_], R]] =
    new WireProtocol[EitherK[M, ?[_], R]] {

      final override val encoder: EitherK[M, Encoded, R] =
        EitherK[M, Encoded, R] {
          M.encoder.mapK(new (Encoded ~> EitherT[Encoded, R, ?]) {
            override def apply[A](ma: Encoded[A]): EitherT[Encoded, R, A] =
              EitherT[Encoded, R, A] {
                val (bytes, resultDecoder) = ma
                val dec = bool.flatMap {
                  case true  => resultDecoder.map(_.asRight[R])
                  case false => rejectionCodec.map(_.asLeft[A])
                }
                (bytes, dec)
              }
          })
        }

      final override val decoder: Decoder[PairE[Invocation[EitherK[M, ?[_], R], ?], Encoder]] =
        M.decoder.map { p =>
          val (invocation, encoder) = (p.first, p.second)
          val eitherKInvocation =
            new Invocation[EitherK[M, ?[_], R], Either[R, p.A]] {
              override def invoke[G[_]](target: EitherK[M, G, R]): G[Either[R, p.A]] =
                invocation.invoke(target.value).value
              override def toString: String = invocation.toString
            }

          val eitherEncoder = Encoder { m: Either[R, p.A] =>
            m match {
              case Right(a) =>
                Encoder.encodeBoth(bool, encoder)(true, a)
              case Left(r) =>
                Encoder.encodeBoth(bool, rejectionCodec)(false, r)
            }
          }

          PairE(eitherKInvocation, eitherEncoder)
        }
    }

  def wrapInvocation[M[_[_]], R, A](
    inner: Invocation[M, A]
  ): Invocation[EitherK[M, ?[_], R], Either[R, A]] =
    new Invocation[EitherK[M, ?[_], R], Either[R, A]] {
      override def invoke[G[_]](target: EitherK[M, G, R]): G[Either[R, A]] =
        inner.invoke(target.value).value
      override def toString: String = inner.toString
    }
}
