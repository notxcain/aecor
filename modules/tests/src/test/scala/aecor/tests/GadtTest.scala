package aecor.tests

import java.nio.ByteBuffer

import aecor.encoding.WireProtocol
import aecor.encoding.WireProtocol.Decoder.DecodingResult
import aecor.macros.boopickleWireProtocol
import cats.{ Id, ~> }
import org.scalatest.{ FunSuite, Matchers }
import io.aecor.liberator.syntax._
import java.util.UUID
import GadtTest._

object GadtTest {

  final case class FooId(value: UUID) extends AnyVal

}

class GadtTest extends FunSuite with Matchers {

  @boopickleWireProtocol
  trait Foo[F[_]] {
    def include(i: Int): F[Unit]
    def scdsc(s: String): F[Int]
    def id: F[FooId]
  }

  val protocol = Foo.aecorWireProtocol

  test("encdec") {
    val uuid = UUID.randomUUID
    val actions = new Foo[Id] {
      override def include(i: Int): Id[Unit] = ()
      override def scdsc(s: String): Id[Int] = s.length
      override def id: Id[FooId] = FooId(uuid)
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

    client.include(1) shouldBe Right(())
    client.scdsc("1234") shouldBe Right(4)
    client.id shouldBe Right(FooId(uuid))
  }
}
