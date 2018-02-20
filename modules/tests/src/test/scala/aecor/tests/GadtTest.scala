package aecor.tests

import java.nio.ByteBuffer

import aecor.encoding.WireProtocol
import aecor.encoding.WireProtocol.Decoder.DecodingResult
import aecor.macros.wireProtocol
import cats.{ Id, ~> }
import org.scalatest.{ FunSuite, Matchers }
import io.aecor.liberator.syntax._

class GadtTest extends FunSuite with Matchers {

  @wireProtocol
  trait Foo[F[_]] {
    def include(i: Int): F[Unit]
    def scdsc(s: String): F[Int]
  }

  val protocol = Foo.aecorWireProtocol

  test("encdec") {
    val actions = new Foo[Id] {
      override def include(i: Int): Id[Unit] = ()
      override def scdsc(s: String): Id[Int] = s.length
    }

    val server: ByteBuffer => DecodingResult[ByteBuffer] = { in =>
      protocol.decoder.decode(in).right.map { p =>
        val r: Id[p.A] = p.left.invoke(actions)
        p.right.encode(r)
      }
    }

    val client = protocol.encoder
      .mapK(new (WireProtocol.Encoded ~> DecodingResult) {
        override def apply[A](fa: (ByteBuffer, WireProtocol.Decoder[A])): DecodingResult[A] =
          server(fa._1).right.flatMap(fa._2.decode)
      })

    client.scdsc("1234") shouldBe Right(4)
  }
}
