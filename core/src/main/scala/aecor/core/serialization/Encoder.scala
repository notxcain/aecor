package aecor.core.serialization

import java.nio.ByteBuffer

import simulacrum.typeclass

@typeclass trait Encoder[A] {
  def encode(a: A): ByteBuffer
}
