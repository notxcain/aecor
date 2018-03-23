package aecor.tests

import java.nio.ByteBuffer

import aecor.ReifiedInvocations
import aecor.encoding.WireProtocol
import aecor.encoding.WireProtocol.Decoder.{ DecodingResult, DecodingResultT }
import aecor.macros.wireProtocol
import cats.{ Applicative, Functor, Id, ~> }
import org.scalatest.{ FunSuite, Matchers }
import io.aecor.liberator.syntax._
import cats.syntax.applicative._
import cats.syntax.functor._
import io.aecor.liberator.FunctorK

class GadtTest extends FunSuite with Matchers {

  @wireProtocol
  trait Foo[F[_]] {
    def include(i: Int): F[Unit]
    def scdsc(s: String): F[Int]
  }

  val protocol = Foo.aecorWireProtocol

  def server[M[_[_]], F[_]: Applicative](actions: M[F])(
    implicit M: WireProtocol[M],
    MI: ReifiedInvocations[M]
  ): ByteBuffer => F[DecodingResult[ByteBuffer]] = { in =>
    M.decoder
      .decode(in) match {
      case Right(p) =>
        val r: F[p.A] = p.left.invoke(actions)
        r.map(a => Right(p.right.encode(a)))
      case Left(e) =>
        (Left(e): DecodingResult[ByteBuffer]).pure[F]
    }

  }

  def client[M[_[_]], F[_]: Functor](
    server: ByteBuffer => F[DecodingResult[ByteBuffer]]
  )(implicit protocol: WireProtocol[M], FK: FunctorK[M]): M[DecodingResultT[F, ?]] =
    protocol.encoder
      .mapK[DecodingResultT[F, ?]](new (WireProtocol.Encoded ~> DecodingResultT[F, ?]) {
        override def apply[A](fa: (ByteBuffer, WireProtocol.Decoder[A])): F[DecodingResult[A]] =
          server(fa._1).map(_.right.flatMap(fa._2.decode))
      })

  test("encdec") {
    val actions = new Foo[Id] {
      override def include(i: Int): Id[Unit] = ()
      override def scdsc(s: String): Id[Int] = s.length
    }

    val fooServer = server(actions)

    val fooClient = client[Foo, Id](fooServer)

    fooClient.scdsc("1234") shouldBe Right(4)
  }
}
