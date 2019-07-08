package aecor.old.streaming

import scala.concurrent.Future

final case class ConsumerId(value: String) extends AnyVal

trait OffsetStore[Offset] {
  def getOffset(tag: String, consumerId: ConsumerId): Future[Option[Offset]]
  def setOffset(tag: String, consumerId: ConsumerId, offset: Offset): Future[Unit]
}
