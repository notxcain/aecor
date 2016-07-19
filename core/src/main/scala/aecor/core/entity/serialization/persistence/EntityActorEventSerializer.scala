package aecor.core.entity.serialization.persistence


import java.time.Instant

import aecor.core.entity.{PersistentEntityEventEnvelope}
import aecor.core.message.MessageId
import aecor.core.serialization.akka.{Codec, CodecSerializer}
import aecor.core.serialization.{protobuf => pb}
import akka.actor.ExtendedActorSystem
import akka.persistence.PersistentRepr
import akka.serialization.{SerializationExtension, SerializerWithStringManifest}
import com.google.protobuf.ByteString

import scala.util.Try

class EntityActorEventCodec(actorSystem: ExtendedActorSystem) extends Codec[PersistentEntityEventEnvelope[AnyRef]] {

  lazy val serialization = SerializationExtension(actorSystem)

  override def manifest(o: PersistentEntityEventEnvelope[AnyRef]): String = ""

  override def decode(bytes: Array[Byte], manifest: String): Try[PersistentEntityEventEnvelope[AnyRef]] =
    pb.PersistentEntityEventEnvelope.validate(bytes).flatMap { dto =>
      serialization.deserialize(dto.payload.toByteArray, dto.serializerId, dto.manifest).map { event =>
        PersistentEntityEventEnvelope(event, Instant.ofEpochMilli(dto.timestamp), MessageId(dto.causedBy))
      }
    }

  override def encode(e: PersistentEntityEventEnvelope[AnyRef]): Array[Byte] = {
    import e._
    val serializer = serialization.findSerializerFor(event)
    val serManifest = serializer match {
      case ser2: SerializerWithStringManifest ⇒
        ser2.manifest(event)
      case _ ⇒
        if (serializer.includeManifest) event.getClass.getName
        else PersistentRepr.Undefined
    }
    pb.PersistentEntityEventEnvelope(serializer.identifier, serManifest, ByteString.copyFrom(serializer.toBinary(event)), timestamp.toEpochMilli, causedBy.value).toByteArray
  }
}

class EntityActorEventSerializer(actorSystem: ExtendedActorSystem) extends CodecSerializer[PersistentEntityEventEnvelope[AnyRef]](actorSystem, new EntityActorEventCodec(actorSystem))
