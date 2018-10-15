package aecor.encoding

import aecor.data.PairE
import io.aecor.liberator.{ Invocation, ReifiedInvocations }
import scodec.{ Decoder, Encoder }
import scodec.bits.BitVector

trait WireProtocol[M[_[_]]] extends ReifiedInvocations[M] {
  def decoder: Decoder[PairE[Invocation[M, ?], Encoder]]
  def encoder: M[WireProtocol.Encoded]
}

object WireProtocol {
  def apply[M[_[_]]](implicit M: WireProtocol[M]): WireProtocol[M] = M
  type Encoded[A] = (BitVector, Decoder[A])
}
