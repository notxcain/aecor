package aecor.tests


import aecor.encoding.WireProtocol
import aecor.encoding.WireProtocol.Decoder.DecodingResult
import aecor.macros.boopickleWireProtocol
import cats.{Applicative, Functor, Id, ~>}
import cats.implicits._
import org.scalatest.{FunSuite, Matchers}
import io.aecor.liberator.syntax._
import java.util.UUID

import BoopickleWireProtocolTest._
import boopickle.Default._
import scodec.bits.BitVector

object BoopickleWireProtocolTest {
  final case class FooId(value: UUID) extends AnyVal
}

class BoopickleWireProtocolTest extends FunSuite with Matchers {

  @boopickleWireProtocol
  trait Foo[F[_]] {
    def include(i: Int): F[Unit]
    def scdsc(s: String): F[Int]
    def id: F[FooId]
  }

  val protocol = WireProtocol[Foo]

  def server[M[_[_]], F[_]: Applicative](
    actions: M[F]
  )(implicit M: WireProtocol[M]): BitVector => F[DecodingResult[BitVector]] = { in =>
    M.decoder
      .decode(in) match {
      case Right(p) =>
        val r: F[p.A] = p.first.invoke(actions)
        r.map(a => Right(p.second.encode(a)))
      case Left(e) =>
        (Left(e): DecodingResult[BitVector]).pure[F]
    }

  }

  type DecodingResultT[F[_], A] = F[DecodingResult[A]]

  def client[M[_[_]], F[_]: Functor](
    server: BitVector => F[DecodingResult[BitVector]]
  )(implicit M: WireProtocol[M]): M[DecodingResultT[F, ?]] =
    M.encoder
      .mapK[DecodingResultT[F, ?]](new (WireProtocol.Encoded ~> DecodingResultT[F, ?]) {
        override def apply[A](fa: (BitVector, WireProtocol.Decoder[A])): F[DecodingResult[A]] =
          server(fa._1).map(_.right.flatMap(fa._2.decode))
      })

  test("encdec") {
    val uuid = UUID.randomUUID
    val actions = new Foo[Id] {
      override def include(i: Int): Id[Unit] = ()
      override def scdsc(s: String): Id[Int] = s.length
      override def id: Id[FooId] = FooId(uuid)
    }

    val fooServer = server(actions)

    val fooClient = client[Foo, Id](fooServer)

    fooClient.include(1) shouldBe Right(())
    fooClient.scdsc("1234") shouldBe Right(4)
    fooClient.id shouldBe Right(FooId(uuid))
  }
}
