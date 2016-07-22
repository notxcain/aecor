package aecor.core.serialization.kafka

import aecor.core.serialization.protobuf.EventEnvelope

class AggregateEventEnvelopeSerializer extends PureSerializer[EventEnvelope] {
  override def serialize(topic: String, data: EventEnvelope): Array[Byte] =
    data.toByteArray
}

class AggregateEventEnvelopeDeserializer extends PureDeserializer[(String, EventEnvelope)] {
  override def deserialize(topic: String, data: Array[Byte]): (String, EventEnvelope) =
    (topic, EventEnvelope.parseFrom(data))
}
