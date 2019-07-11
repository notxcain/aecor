package aecor.runtime.akkageneric

import aecor.encoding.{ KeyDecoder, KeyEncoder, WireProtocol }
import aecor.macros.boopickle.BoopickleWireProtocol
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import cats.tagless.FunctorK

trait Counter[F[_]] {
  def increment: F[Long]
  def decrement: F[Long]
  def value: F[Long]
}

object Counter {
  def inmem[F[_]: Sync]: F[Counter[F]] =
    Ref[F].of(0L).map { ref =>
      new Counter[F] {
        override def increment: F[Long] = ref.update(_ + 1L) >> value
        override def decrement: F[Long] = ref.update(_ - 1L) >> value
        override def value: F[Long] = ref.get
      }
    }

  import boopickle.Default._

  implicit def wireProtocol: WireProtocol[Counter] = BoopickleWireProtocol.derive
  implicit def functorK: FunctorK[Counter] = cats.tagless.Derive.functorK
}

final case class CounterId(value: String) extends AnyVal
object CounterId {
  implicit val keyEncoder: KeyEncoder[CounterId] = KeyEncoder.anyVal
  implicit val keyDecoder: KeyDecoder[CounterId] = KeyDecoder.anyVal
}
