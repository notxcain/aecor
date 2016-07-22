package aecor.core.serialization

import java.nio.ByteBuffer

import cats.data.Xor
import simulacrum.typeclass

@typeclass trait Decoder[A] {
  def decode(bytes: ByteBuffer): Decoder.Result[A]
}

object Decoder {
  type Result[A] = Xor[DecodingFailure, A]
}

case class DecodingFailure(description: String) extends Exception {
  final override def fillInStackTrace(): Throwable = this
}

object DecodingFailure {
  def fromThrowable(t: Throwable): DecodingFailure = t match {
    case (d: DecodingFailure) => d
    case other =>
      val sw = new java.io.StringWriter
      val pw = new java.io.PrintWriter(sw)
      other.printStackTrace(pw)
      DecodingFailure(sw.toString)
  }
}

