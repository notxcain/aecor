package aecor.core.serialization.kafka

import aecor.core.serialization.protobuf.EventEnvelope

class EventEnvelopeSerializer extends PureSerializer[EventEnvelope] {
  override def serialize(topic: String, data: EventEnvelope): Array[Byte] =
    data.toByteArray
}

class EventEnvelopeDeserializer extends PureDeserializer[(String, EventEnvelope)] {
  override def deserialize(topic: String, data: Array[Byte]): (String, EventEnvelope) =
    (topic, EventEnvelope.parseFrom(data))
}
