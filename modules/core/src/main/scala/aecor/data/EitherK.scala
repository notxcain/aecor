package aecor.data

import aecor.encoding.WireProtocol
import aecor.encoding.WireProtocol.Encoded
import cats.data.EitherT
import cats.~>
import io.aecor.liberator.{ FunctorK, Invocation }
import cats.implicits._
import scodec.{ Codec, Decoder, Encoder }
import scodec.codecs._

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
  implicit def wireProtocol[M[_[_]], F[_], R](implicit M: WireProtocol[M],
                                              R: Codec[R]): WireProtocol[EitherK[M, ?[_], R]] =
    new WireProtocol[EitherK[M, ?[_], R]] {

      override def mapK[G[_], H[_]](mf: EitherK[M, G, R], fg: ~>[G, H]): EitherK[M, H, R] =
        mf.mapK(fg)

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
                    override def toString: String = fa.toString
                  }
                }
            }
          )
        }

      override val encoder: EitherK[M, Encoded, R] =
        EitherK[M, Encoded, R] {
          M.mapInvocations(new (Invocation[M, ?] ~> EitherT[Encoded, R, ?]) {
            override def apply[A](ma: Invocation[M, A]): EitherT[Encoded, R, A] =
              EitherT[Encoded, R, A] {
                val (bytes, decM) = ma.invoke(M.encoder)
                val dec = bool.flatMap {
                  case true  => decM.map(_.asRight[R])
                  case false => R.map(_.asLeft[A])
                }
                (bytes, dec)
              }
          })
        }

      override def decoder: Decoder[PairE[Invocation[EitherK[M, ?[_], R], ?], Encoder]] =
        M.decoder.map { p =>
          val (invocation, encoder) = (p.first, p.second)
          val invocationR =
            new Invocation[EitherK[M, ?[_], R], Either[R, p.A]] {
              override def invoke[G[_]](target: EitherK[M, G, R]): G[Either[R, p.A]] =
                invocation.invoke(target.value).value
              override def toString: String = invocation.toString
            }

          val encoderR = Encoder { m: Either[R, p.A] =>
            m match {
              case Right(a) =>
                Encoder.encodeBoth(bool, encoder)(true, a)
              case Left(r) =>
                Encoder.encodeBoth(bool, R)(false, r)
            }
          }

          PairE(invocationR, encoderR)
        }
    }
}
