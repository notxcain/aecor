package aecor.runtime.akkapersistence

import aecor.runtime.akkapersistence.serialization.PersistentDecoder.DecodingResult
import aecor.runtime.akkapersistence.serialization.{
  PersistentDecoder,
  PersistentEncoder,
  PersistentRepr
}

sealed abstract class SnapshotPolicy[+E] extends Product with Serializable

object SnapshotPolicy {
  def never[E]: SnapshotPolicy[E] = Never.asInstanceOf[SnapshotPolicy[E]]

  def eachNumberOfEvents[E: PersistentEncoder: PersistentDecoder](
    numberOfEvents: Int
  ): SnapshotPolicy[E] = EachNumberOfEvents(numberOfEvents)

  private[akkapersistence] case object Never extends SnapshotPolicy[Nothing]

  private[akkapersistence] final case class EachNumberOfEvents[
    State: PersistentEncoder: PersistentDecoder
  ](numberOfEvents: Int)
      extends SnapshotPolicy[State] {
    def encode(state: State): PersistentRepr = PersistentEncoder[State].encode(state)
    def decode(repr: PersistentRepr): DecodingResult[State] = PersistentDecoder[State].decode(repr)
  }
}
