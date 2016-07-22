package aecor.core.aggregate.serialization

import java.time.Instant

import aecor.core.aggregate.{AggregateEventEnvelope, CommandId, EventId}
import aecor.core.serialization.akka.{Codec, CodecSerializer}
import aecor.core.serialization.{protobuf => pb}
import akka.actor.ExtendedActorSystem
import akka.persistence.PersistentRepr
import akka.serialization.{SerializationExtension, SerializerWithStringManifest}
import com.google.protobuf.ByteString

import scala.util.Try

class PersistentEntityEventEnvelopeCodec(actorSystem: ExtendedActorSystem) extends Codec[AggregateEventEnvelope[AnyRef]] {

  lazy val serialization = SerializationExtension(actorSystem)

  override def manifest(o: AggregateEventEnvelope[AnyRef]): String = ""

  override def decode(bytes: Array[Byte], manifest: String): Try[AggregateEventEnvelope[AnyRef]] =
    pb.PersistentAggregateEventEnvelope.validate(bytes).flatMap { dto =>
      serialization.deserialize(dto.payload.toByteArray, dto.serializerId, dto.manifest).map { event =>
        AggregateEventEnvelope(EventId(dto.id), event, Instant.ofEpochMilli(dto.timestamp), CommandId(dto.causedBy))
      }
    }

  override def encode(e: AggregateEventEnvelope[AnyRef]): Array[Byte] = {
    import e._
    val serializer = serialization.findSerializerFor(event)
    val serManifest = serializer match {
      case ser2: SerializerWithStringManifest ⇒
        ser2.manifest(event)
      case _ ⇒
        if (serializer.includeManifest) event.getClass.getName
        else PersistentRepr.Undefined
    }
    pb.PersistentAggregateEventEnvelope(e.id.value, serializer.identifier, serManifest, ByteString.copyFrom(serializer.toBinary(event)), timestamp.toEpochMilli, causedBy.value).toByteArray
  }
}

class PersistentEntityEventEnvelopeSerializer(actorSystem: ExtendedActorSystem)
  extends CodecSerializer[AggregateEventEnvelope[AnyRef]](actorSystem, new PersistentEntityEventEnvelopeCodec(actorSystem))
