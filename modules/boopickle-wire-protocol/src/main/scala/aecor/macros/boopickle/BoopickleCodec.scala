package aecor.macros.boopickle
import boopickle.Pickler
import scodec.bits.BitVector
import scodec.{ Attempt, Codec, DecodeResult, Decoder, Encoder, Err, SizeBound }
import _root_.boopickle.Default._

import scala.util.Try

object BoopickleCodec {
  def encoder[A](implicit pickler: Pickler[A]): Encoder[A] = new Encoder[A] {
    override def encode(value: A): Attempt[BitVector] =
      Attempt.successful(BitVector(Pickle.intoBytes(value)))
    override def sizeBound: SizeBound = SizeBound.unknown
  }
  def decoder[A](implicit pickler: Pickler[A]): Decoder[A] = new Decoder[A] {
    override def decode(bits: BitVector): Attempt[DecodeResult[A]] =
      Unpickle
        .apply[A]
        .tryFromBytes(bits.toByteBuffer)
        .fold(
          s => Attempt.failure(Err(s.getMessage)),
          a => Attempt.successful(DecodeResult(a, BitVector.empty))
        )
  }

  def codec[A](implicit pickler: Pickler[A]): Codec[A] = Codec(encoder[A], decoder[A])

  def attemptFromTry[A](ta: Try[A]): Attempt[DecodeResult[A]] = ta.fold(
    s => Attempt.failure(Err(s.getMessage)),
    a => Attempt.successful(DecodeResult(a, BitVector.empty))
  )
}
