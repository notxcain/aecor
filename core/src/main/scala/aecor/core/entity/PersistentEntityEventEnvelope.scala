package aecor.core.entity

import java.time.Instant

import aecor.core.message.MessageId

import scala.reflect.ClassTag

case class PersistentEntityEventEnvelope[+Event](event: Event, timestamp: Instant, causedBy: MessageId) {
  def cast[EE: ClassTag]: Option[PersistentEntityEventEnvelope[EE]] = event match {
    case e: EE => Some(this.asInstanceOf[PersistentEntityEventEnvelope[EE]])
    case other => None
  }
}
