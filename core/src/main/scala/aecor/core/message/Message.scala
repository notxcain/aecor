package aecor.core.message

import java.util.UUID

import akka.NotUsed

object MessageId {
  def generate: MessageId =
    MessageId(UUID.randomUUID().toString)

  def apply(entityName: String, entityId: String, entityVersion: Long): MessageId =
    MessageId(entityName + "-" + entityId + "#" + entityVersion)
}
case class MessageId(value: String) extends AnyVal
case class Message[Payload, Ack](id: MessageId, payload: Payload, ack: Ack)

object Message {
  def apply[Payload](payload: Payload): Message[Payload, NotUsed] = Message(MessageId.generate, payload, NotUsed)
  def apply[Payload, Ack](payload: Payload, deliveryAck: Ack): Message[Payload, Ack] = Message(MessageId.generate, payload, deliveryAck)
  def apply[Payload](id: String, payload: Payload): Message[Payload, NotUsed] = Message(MessageId(id), payload, NotUsed)
}