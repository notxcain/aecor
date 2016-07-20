package aecor.core.serialization

import java.util

import aecor.core.serialization.protobuf.ExternalEntityEventEnvelope
import org.apache.kafka.common.serialization.{Deserializer, Serializer}

trait PureDeserializer[A] extends Deserializer[A] {
  final override def configure(configs: util.Map[String, _], isKey: Boolean): Unit = ()
  final override def close(): Unit = ()
}

class EntityEventEnvelopeSerde extends Serializer[ExternalEntityEventEnvelope] with PureDeserializer[(String, ExternalEntityEventEnvelope)] {
  override def serialize(topic: String, data: ExternalEntityEventEnvelope): Array[Byte] =
    data.toByteArray

  override def deserialize(topic: String, data: Array[Byte]): (String, ExternalEntityEventEnvelope) =
    (topic, ExternalEntityEventEnvelope.parseFrom(data))
}
