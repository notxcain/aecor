package aecor.core.serialization

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

trait Encoder[A] {
  def encode(a: A): ByteBuffer
}

object Encoder {
  implicit def fromCirceEncoder[A](implicit circeEncoder: io.circe.Encoder[A]): Encoder[A] = new Encoder[A] {
    override def encode(a: A): ByteBuffer = ByteBuffer.wrap(circeEncoder(a).noSpaces.getBytes(StandardCharsets.UTF_8))
  }
  def apply[A](implicit encoder: Encoder[A]): Encoder[A] = encoder
}
