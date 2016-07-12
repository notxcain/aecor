package aecor.core.process

import aecor.core.message.MessageId

sealed trait ProcessEvent
object ProcessEvent {
  case class EventEnvelope[E](id: MessageId, event: E) extends ProcessEvent
  case class CommandRejected(rejection: Any, deliveryId: Long) extends ProcessEvent
  case class CommandAccepted(deliveryId: Long) extends ProcessEvent
}
