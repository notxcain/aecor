package aecor.core.entity.serialization.persistence


import java.time.Instant

import aecor.core.entity.{EntityActorEvent, EntityEventEnvelope, EventPublished}
import aecor.core.message.MessageId
import aecor.core.serialization.akka.{Codec, CodecSerializer}
import aecor.core.serialization.{protobuf => pb}
import akka.actor.ExtendedActorSystem
import akka.persistence.PersistentRepr
import akka.serialization.{SerializationExtension, SerializerWithStringManifest}
import com.google.protobuf.ByteString

import scala.util.Try

case class EntityActorEventCodec(actorSystem: ExtendedActorSystem) extends Codec[EntityActorEvent] {
  val EntityEventEnvelopeManifest = "EntityEventEnvelope"
  val EventPublishedManifest = "EventPublished"

  lazy val serialization = SerializationExtension(actorSystem)

  override def manifest(o: EntityActorEvent): String = o match {
    case e: EntityEventEnvelope[_] => EntityEventEnvelopeManifest
    case e: EventPublished => EventPublishedManifest
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): Option[Try[EntityActorEvent]] =
    Some(manifest).collect {
      case EntityEventEnvelopeManifest =>
        pb.EntityEventEnvelope.validate(bytes).flatMap { dto =>
          serialization.deserialize(dto.payload.toByteArray, dto.serializerId, dto.manifest).map { event =>
            EntityEventEnvelope(MessageId(dto.id), event, Instant.ofEpochMilli(dto.timestamp), MessageId(dto.causedBy))
          }
        }
      case EventPublishedManifest =>
        pb.EventPublished.validate(bytes).map { dto =>
          EventPublished(dto.eventNr)
        }
    }

  override def toBinary(o: EntityActorEvent): Array[Byte] = o match {
    case EntityEventEnvelope(id, payload, timestamp, causedBy) =>
      val event = payload.asInstanceOf[AnyRef]
      val serializer = serialization.findSerializerFor(event)
      val serManifest = serializer match {
        case ser2: SerializerWithStringManifest ⇒
          ser2.manifest(event)
        case _ ⇒
          if (serializer.includeManifest) event.getClass.getName
          else PersistentRepr.Undefined
      }
      pb.EntityEventEnvelope(id.value, serializer.identifier, serManifest, ByteString.copyFrom(serializer.toBinary(event)), timestamp.toEpochMilli, causedBy.value).toByteArray
    case EventPublished(eventNr) =>
      pb.EventPublished(eventNr).toByteArray
  }
}

class EntityActorEventSerializer(actorSystem: ExtendedActorSystem) extends CodecSerializer[EntityActorEvent](actorSystem, new EntityActorEventCodec(actorSystem))
