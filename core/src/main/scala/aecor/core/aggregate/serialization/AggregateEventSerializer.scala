package aecor.core.aggregate.serialization

import java.time.Instant

import aecor.core.aggregate.{AggregateEvent, EventId}
import aecor.core.serialization.akka.{Codec, CodecSerializer, SerializationHelper}
import aecor.core.serialization.{protobuf => pb}
import akka.actor.ExtendedActorSystem
import akka.serialization.{Serialization, SerializationExtension}
import com.google.protobuf.ByteString

import scala.util.Try

class AggregateEventCodec(actorSystem: ExtendedActorSystem) extends Codec[AggregateEvent[AnyRef]] {


  @volatile
  private var ser: Serialization = _
  def serialization: Serialization = {
    if (ser == null) ser = SerializationExtension(actorSystem)
    ser
  }

  @volatile
  private var hel: SerializationHelper = _
  def helper: SerializationHelper = {
    if (hel == null) hel = SerializationHelper(serialization)
    hel
  }

  override def manifest(o: AggregateEvent[AnyRef]): String = ""

  override def decode(bytes: Array[Byte], manifest: String): Try[AggregateEvent[AnyRef]] =
    pb.AggregateEvent.validate(bytes).flatMap { dto =>
      serialization.deserialize(dto.payload.toByteArray, dto.serializerId, dto.manifest).map { event =>
        AggregateEvent(EventId(dto.id), event, Instant.ofEpochMilli(dto.timestamp))
      }
    }

  override def encode(e: AggregateEvent[AnyRef]): Array[Byte] = {
    import e._
    val eventRepr = helper.serialize(event)
    pb.AggregateEvent(e.id.value, eventRepr.serializerId, eventRepr.manifest, ByteString.copyFrom(eventRepr.bytes), timestamp.toEpochMilli).toByteArray
  }
}

class AggregateEventSerializer(actorSystem: ExtendedActorSystem)
  extends CodecSerializer[AggregateEvent[AnyRef]](actorSystem, new AggregateEventCodec(actorSystem))
