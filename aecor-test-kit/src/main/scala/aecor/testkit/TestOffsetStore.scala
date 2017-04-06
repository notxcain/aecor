package aecor.testkit

import aecor.streaming.{ ConsumerId, OffsetStore }
import cats.Applicative
import cats.data.StateT

object TestOffsetStore {
  def apply[F[_]: Applicative, S, A](
    extract: S => Map[(String, ConsumerId), A],
    update: (S, Map[(String, ConsumerId), A]) => S
  ): OffsetStore[StateT[F, S, ?], A] =
    new TestOffsetStore(extract, update)
}

class TestOffsetStore[F[_]: Applicative, S, A](extract: S => Map[(String, ConsumerId), A],
                                               update: (S, Map[(String, ConsumerId), A]) => S)
    extends OffsetStore[StateT[F, S, ?], A] {
  override def getOffset(tag: String, consumerId: ConsumerId): StateT[F, S, Option[A]] =
    StateT
      .inspect[F, Map[(String, ConsumerId), A], Option[A]](_.get(tag -> consumerId))
      .transformS(extract, update)

  override def setOffset(tag: String, consumerId: ConsumerId, offset: A): StateT[F, S, Unit] =
    StateT
      .modify[F, Map[(String, ConsumerId), A]](_.updated(tag -> consumerId, offset))
      .transformS(extract, update)

}
