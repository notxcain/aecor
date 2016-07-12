package aecor.core.serialization

import java.util

import aecor.core.serialization.protobuf.DomainEvent
import org.apache.kafka.common.serialization.{Deserializer, Serializer}

class DomainEventSerialization extends Serializer[DomainEvent] with Deserializer[(String, DomainEvent)] {
  override def serialize(topic: String, data: DomainEvent): Array[Byte] =
    data.toByteArray

  override def configure(configs: util.Map[String, _], isKey: Boolean): Unit = {}

  override def close(): Unit = {}

  override def deserialize(topic: String, data: Array[Byte]): (String, DomainEvent) =
    (topic, DomainEvent.parseFrom(data))
}
