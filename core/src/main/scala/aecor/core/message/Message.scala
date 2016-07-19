package aecor.core.message

import java.util.UUID

case class MessageId(value: String) extends AnyVal

object MessageId {
  def generate: MessageId =
    MessageId(UUID.randomUUID().toString)
}

case class Message[Payload, Ack](id: MessageId, payload: Payload, ack: Ack)