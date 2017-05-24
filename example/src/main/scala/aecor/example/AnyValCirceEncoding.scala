package aecor.example

import io.circe.{ Decoder, Encoder }
import shapeless.Unwrapped

trait AnyValCirceEncoding {
  implicit def anyValEncoder[V, U](implicit ev: V <:< AnyVal,
                                   V: Unwrapped.Aux[V, U],
                                   encoder: Encoder[U]): Encoder[V] =
    encoder.contramap(V.unwrap)

  implicit def anyValDecoder[V, U](implicit ev: V <:< AnyVal,
                                   V: Unwrapped.Aux[V, U],
                                   decoder: Decoder[U]): Decoder[V] =
    decoder.map(V.wrap)
}

object AnyValCirceEncoding extends AnyValCirceEncoding

object CirceSupport
    extends de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
    with AnyValCirceEncoding
