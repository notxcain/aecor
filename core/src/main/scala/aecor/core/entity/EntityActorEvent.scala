package aecor.core.entity

import java.time.Instant

import aecor.core.message.MessageId

import scala.reflect.ClassTag

sealed trait EntityActorEvent
case class EntityEventEnvelope[+Event](id: MessageId, event: Event, timestamp: Instant, causedBy: MessageId) extends EntityActorEvent {
  def cast[EE: ClassTag]: Option[EntityEventEnvelope[EE]] = event match {
    case e: EE => Some(this.asInstanceOf[EntityEventEnvelope[EE]])
    case other => None
  }
}
case class EventPublished(eventNr: Long) extends EntityActorEvent
