package aecor.core.aggregate.serialization

import java.time.Instant

import aecor.core.aggregate.{AggregateEvent, CommandId, EventId}
import aecor.core.serialization.akka.{Codec, CodecSerializer}
import aecor.core.serialization.{protobuf => pb}
import akka.actor.ExtendedActorSystem
import akka.persistence.PersistentRepr
import akka.serialization.{SerializationExtension, SerializerWithStringManifest}
import com.google.protobuf.ByteString

import scala.util.Try

class AggregateEventCodec(actorSystem: ExtendedActorSystem) extends Codec[AggregateEvent[AnyRef]] {

  lazy val serialization = SerializationExtension(actorSystem)

  override def manifest(o: AggregateEvent[AnyRef]): String = ""

  override def decode(bytes: Array[Byte], manifest: String): Try[AggregateEvent[AnyRef]] =
    pb.AggregateEvent.validate(bytes).flatMap { dto =>
      serialization.deserialize(dto.payload.toByteArray, dto.serializerId, dto.manifest).map { event =>
        AggregateEvent(EventId(dto.id), event, Instant.ofEpochMilli(dto.timestamp), CommandId(dto.causedBy))
      }
    }

  override def encode(e: AggregateEvent[AnyRef]): Array[Byte] = {
    import e._
    val serializer = serialization.findSerializerFor(event)
    val serManifest = serializer match {
      case ser2: SerializerWithStringManifest ⇒
        ser2.manifest(event)
      case _ ⇒
        if (serializer.includeManifest) event.getClass.getName
        else PersistentRepr.Undefined
    }
    pb.AggregateEvent(e.id.value, serializer.identifier, serManifest, ByteString.copyFrom(serializer.toBinary(event)), timestamp.toEpochMilli, causedBy.value).toByteArray
  }
}

class AggregateEventSerializer(actorSystem: ExtendedActorSystem)
  extends CodecSerializer[AggregateEvent[AnyRef]](actorSystem, new AggregateEventCodec(actorSystem))
