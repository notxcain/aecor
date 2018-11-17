package aecor.tests

import aecor.encoding.WireProtocol
import aecor.macros.boopickleWireProtocol
import cats.{ Applicative, Functor, Id, ~> }
import cats.implicits._
import org.scalatest.{ FunSuite, Matchers }
import java.util.UUID

import BoopickleWireProtocolTest._
import boopickle.Default._
import cats.tagless.{ FunctorK, autoFunctorK }
import cats.tagless.syntax.functorK._
import scodec.{ Attempt, Decoder }
import scodec.bits.BitVector

object BoopickleWireProtocolTest {
  final case class FooId(value: UUID) extends AnyVal
}

class BoopickleWireProtocolTest extends FunSuite with Matchers {

  @boopickleWireProtocol
  @autoFunctorK
  trait Foo[F[_]] {
    def include(i: Int): F[Unit]
    def scdsc(s: String): F[Int]
    def id: F[FooId]
  }

  def server[M[_[_]], F[_]: Applicative](
    actions: M[F]
  )(implicit M: WireProtocol[M]): BitVector => F[Attempt[BitVector]] = { in =>
    M.decoder
      .decodeValue(in) match {
      case Attempt.Successful(p) =>
        val r: F[p.A] = p.first.run(actions)
        r.map(a => p.second.encode(a))
      case Attempt.Failure(cause) =>
        Attempt.failure[BitVector](cause).pure[F]
    }

  }

  type DecodingResultT[F[_], A] = F[Attempt[A]]

  def client[M[_[_]], F[_]: Functor](
    server: BitVector => F[Attempt[BitVector]]
  )(implicit M: WireProtocol[M], MI: FunctorK[M]): M[DecodingResultT[F, ?]] =
    M.encoder
      .mapK[DecodingResultT[F, ?]](new (WireProtocol.Encoded ~> DecodingResultT[F, ?]) {
        override def apply[A](fa: (BitVector, Decoder[A])): F[Attempt[A]] =
          server(fa._1).map(_.flatMap(fa._2.decodeValue))
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

    fooClient.include(1).toEither shouldBe Right(())
    fooClient.scdsc("1234").toEither shouldBe Right(4)
    fooClient.id.toEither shouldBe Right(FooId(uuid))
  }
}
