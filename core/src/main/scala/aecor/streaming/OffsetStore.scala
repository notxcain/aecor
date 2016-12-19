package aecor.streaming

import scala.concurrent.Future

trait OffsetStore[Offset] {
  def getOffset(tag: String, consumerId: String): Future[Option[Offset]]
  def setOffset(tag: String, consumerId: String, offset: Offset): Future[Unit]
}
