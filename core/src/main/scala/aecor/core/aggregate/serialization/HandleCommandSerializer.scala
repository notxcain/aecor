package aecor.core.aggregate.serialization

import aecor.core.aggregate.{CommandId, AggregateCommand}
import aecor.core.serialization.akka.{Codec, CodecSerializer}
import aecor.core.serialization.{protobuf => pb}
import akka.actor.ExtendedActorSystem
import akka.persistence.PersistentRepr
import akka.serialization.{SerializationExtension, SerializerWithStringManifest}
import com.google.protobuf.ByteString

import scala.util.Try

class HandleCommandCodec(actorSystem: ExtendedActorSystem) extends Codec[AggregateCommand[AnyRef]] {
  lazy val serialization = SerializationExtension(actorSystem)

  override def manifest(o: AggregateCommand[AnyRef]): String = ""

  override def decode(bytes: Array[Byte], manifest: String): Try[AggregateCommand[AnyRef]] =
    pb.CommandMessage.validate(bytes).flatMap { dto =>
      serialization.deserialize(dto.payload.toByteArray, dto.serializerId, dto.manifest).map { command =>
        AggregateCommand(CommandId(dto.id), command)
      }
    }

  override def encode(o: AggregateCommand[AnyRef]): Array[Byte] = {
    import o._
    val serializer = serialization.findSerializerFor(command)
    val serManifest = serializer match {
      case ser2: SerializerWithStringManifest ⇒
        ser2.manifest(command)
      case _ ⇒
        if (serializer.includeManifest) command.getClass.getName
        else PersistentRepr.Undefined
    }
    pb.CommandMessage(o.id.value, serializer.identifier, serManifest, ByteString.copyFrom(serializer.toBinary(command))).toByteArray
  }
}

class HandleCommandSerializer(system: ExtendedActorSystem) extends CodecSerializer(system, new HandleCommandCodec(system))