package aecor.core.process

import aecor.core.aggregate.{CommandId, EventId}

sealed trait ProcessEvent
object ProcessEvent {
  case class EventEnvelope[E](id: EventId, event: E) extends ProcessEvent
  case class CommandRejected(rejection: Any, commandId: CommandId) extends ProcessEvent
  case class CommandAccepted(commandId: CommandId) extends ProcessEvent
}
