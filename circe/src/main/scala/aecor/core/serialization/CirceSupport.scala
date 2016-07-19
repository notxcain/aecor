package aecor.core.serialization
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import aecor.core.serialization.Decoder.Result
import io.circe.jawn

trait CirceSupport {
  implicit def fromCirceEncoder[A](implicit circeEncoder: io.circe.Encoder[A]): Encoder[A] = new Encoder[A] {
    override def encode(a: A): ByteBuffer =
      ByteBuffer.wrap(circeEncoder(a).noSpaces.getBytes(StandardCharsets.UTF_8))
  }
  implicit def fromCirceDecoder[A](implicit circeDecoder: io.circe.Decoder[A]): Decoder[A] = new Decoder[A] {
    override def decode(bytes: ByteBuffer): Result[A] =
      jawn.parseByteBuffer(bytes).flatMap(circeDecoder.decodeJson).leftMap(DecodingFailure.fromThrowable)
  }
}

object CirceSupport extends CirceSupport