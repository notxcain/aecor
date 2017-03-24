package aecor.streaming

import cats.~>

final case class ConsumerId(value: String) extends AnyVal

trait OffsetStore[F[_], Offset] { outer =>
  def getOffset(tag: String, consumerId: ConsumerId): F[Option[Offset]]
  def setOffset(tag: String, consumerId: ConsumerId, offset: Offset): F[Unit]
  def mapK[G[_]](f: F ~> G): OffsetStore[G, Offset] =
    new OffsetStore[G, Offset] {
      override def getOffset(tag: String, consumerId: ConsumerId): G[Option[Offset]] =
        f(outer.getOffset(tag, consumerId))
      override def setOffset(tag: String, consumerId: ConsumerId, offset: Offset): G[Unit] =
        f(outer.setOffset(tag, consumerId, offset))
    }
}
