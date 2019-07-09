package aecor.testkit

import aecor.data.PairE
import aecor.encoding.WireProtocol
import aecor.encoding.WireProtocol.{ Encoded, Invocation }
import cats.data.ReaderT
import cats.tagless.FunctorK
import cats.tagless.implicits._
import cats.~>
import scodec.bits.BitVector
import scodec.{ Codec, Decoder, Encoder }

final case class ReaderK[M[_[_]], A, F[_]](value: M[ReaderT[F, A, ?]]) extends AnyVal {
  def unwrap(implicit M: FunctorK[M]): M[λ[α => A => F[α]]] =
    value.mapK(new (ReaderT[F, A, ?] ~> λ[α => A => F[α]]) {
      override def apply[X](fa: ReaderT[F, A, X]): A => F[X] = fa.run
    })

  def mapK[G[_]](fg: F ~> G)(implicit M: FunctorK[M]): ReaderK[M, A, G] =
    ReaderK(value.mapK(new (ReaderT[F, A, ?] ~> ReaderT[G, A, ?]) {
      override def apply[X](fa: ReaderT[F, A, X]): ReaderT[G, A, X] =
        fa.mapK(fg)
    }))
}

object ReaderK {
  implicit def wireProtocol[M[_[_]]: FunctorK, A](implicit M: WireProtocol[M],
                                                  A: Codec[A]): WireProtocol[ReaderK[M, A, ?[_]]] =
    new WireProtocol[ReaderK[M, A, ?[_]]] {

      final override val encoder: ReaderK[M, A, Encoded] =
        ReaderK[M, A, Encoded] {
          M.encoder.mapK(new (Encoded ~> ReaderT[Encoded, A, ?]) {
            override def apply[B](ma: Encoded[B]): ReaderT[Encoded, A, B] =
              ReaderT[Encoded, A, B] { a =>
                val (bytes, resultDecoder) = ma

                A.encode(a).map(_ ++ bytes).getOrElse(BitVector.empty)

                (bytes, resultDecoder)
              }
          })
        }

      final override val decoder: Decoder[PairE[Invocation[ReaderK[M, A, ?[_]], ?], Encoder]] =
        A.asDecoder.flatMap { a =>
          M.decoder.map { p =>
            val (invocation, encoder) = (p.first, p.second)
            val readerKInvocation: Invocation[ReaderK[M, A, ?[_]], p.A] =
              new Invocation[ReaderK[M, A, ?[_]], p.A] {
                override def run[F[_]](target: ReaderK[M, A, F]): F[p.A] =
                  invocation.run(target.value).run(a)
                override def toString: String = invocation.toString
              }
            PairE(readerKInvocation, encoder)
          }
        }

    }

  implicit def functorK[M[_[_]]: FunctorK, A]: FunctorK[ReaderK[M, A, ?[_]]] =
    new FunctorK[ReaderK[M, A, ?[_]]] {
      override def mapK[F[_], G[_]](af: ReaderK[M, A, F])(fk: ~>[F, G]): ReaderK[M, A, G] =
        af.mapK(fk)
    }

  object syntax {
    implicit final class ReaderKSyntaxImpl[M[_[_]], F[_], A](val self: M[ReaderT[F, A, ?]])
        extends AnyVal {
      def toReaderK: ReaderK[M, A, F] = ReaderK(self)
    }
  }
}
