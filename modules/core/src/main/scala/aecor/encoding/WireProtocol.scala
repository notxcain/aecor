package aecor.encoding

import aecor.data.PairE
import aecor.encoding.WireProtocol.DecoderK
import scodec.bits.BitVector
import scodec.{ Decoder, Encoder }

trait WireProtocol[M[_[_]]] {
  def decoder: DecoderK[M]
  def encoder: M[WireProtocol.Encoded]
}

object WireProtocol {
  def apply[M[_[_]]](implicit M: WireProtocol[M]): WireProtocol[M] = M
  type Encoded[A] = (BitVector, Decoder[A])
  type DecoderK[M[_[_]]] = Decoder[PairE[Invocation[M, ?], Encoder]]
  trait Invocation[M[_[_]], A] {
    def run[F[_]](mf: M[F]): F[A]
  }
}
