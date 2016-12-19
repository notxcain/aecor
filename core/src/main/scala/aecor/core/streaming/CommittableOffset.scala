package aecor.core.streaming

import scala.concurrent.Future

trait CommittableOffset[Offset] extends Committable {
  def value: Offset
  override def toString: String = s"CommittableOffset($value)"
}

object CommittableOffset {
  def apply[Offset](offset: Offset, committer: Offset => Future[Unit]): CommittableOffset[Offset] =
    new CommittableOffset[Offset] {
      override def value: Offset = offset
      override def commit(): Future[Unit] = committer(value)
    }
}

trait Committable {
  def commit(): Future[Unit]
}
