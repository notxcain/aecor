package aecor.runtime.akkapersistence

import aecor.runtime.akkapersistence.SnapshotPolicy.{ EachNumberOfEvents, Never }
import aecor.runtime.akkapersistence.serialization.PersistentDecoder.DecodingResult
import aecor.runtime.akkapersistence.serialization.{
  PersistentDecoder,
  PersistentEncoder,
  PersistentRepr
}

sealed abstract class SnapshotPolicy[+E] extends Product with Serializable {
  def pluginId: String = this match {
    case Never                                   => ""
    case EachNumberOfEvents(_, snapshotPluginId) => snapshotPluginId
  }
}

object SnapshotPolicy {
  def never[E]: SnapshotPolicy[E] = Never

  def eachNumberOfEvents[E: PersistentEncoder: PersistentDecoder](
    numberOfEvents: Int,
    pluginId: String
  ): SnapshotPolicy[E] = EachNumberOfEvents(numberOfEvents, pluginId)

  private[akkapersistence] case object Never extends SnapshotPolicy[Nothing]

  private[akkapersistence] final case class EachNumberOfEvents[
    State: PersistentEncoder: PersistentDecoder
  ](numberOfEvents: Int, snapshotPluginId: String)
      extends SnapshotPolicy[State] {
    def encode(state: State): PersistentRepr = PersistentEncoder[State].encode(state)
    def decode(repr: PersistentRepr): DecodingResult[State] = PersistentDecoder[State].decode(repr)
  }
}
