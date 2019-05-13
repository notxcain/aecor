package aecor.encoding

import aecor.data.PairE
import aecor.encoding.WireProtocol.Invocation
import scodec.bits.BitVector
import scodec.{ Decoder, Encoder }

trait WireProtocol[M[_[_]]] {
  def decoder: Decoder[PairE[Invocation[M, ?], Encoder]]
  def encoder: M[WireProtocol.Encoded]
}

object WireProtocol {
  def apply[M[_[_]]](implicit M: WireProtocol[M]): WireProtocol[M] = M
  type Encoded[A] = (BitVector, Decoder[A])
  trait Invocation[M[_[_]], A] {
    def run[F[_]](mf: M[F]): F[A]
  }
}
