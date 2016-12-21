package aecor.example

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import aecor.serialization.{ DecodingFailure, PersistentDecoder, PersistentEncoder }
import io.circe.{ Decoder, Encoder, jawn }
object persistentEncoderUtil {
  def circePersistentEncoder[A](implicit encoder: Encoder[A]): PersistentEncoder[A] =
    PersistentEncoder.instance(e => "" -> encoder(e).noSpaces.getBytes(StandardCharsets.UTF_8))

  def circePersistentDecoder[A](implicit decoder: Decoder[A]): PersistentDecoder[A] =
    PersistentDecoder.instance(
      repr =>
        jawn
          .parseByteBuffer(ByteBuffer.wrap(repr.payload))
          .right
          .flatMap(decoder.decodeJson)
          .left
          .map(DecodingFailure.fromThrowable)
    )
}
