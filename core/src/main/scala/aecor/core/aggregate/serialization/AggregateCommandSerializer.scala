package aecor.core.aggregate.serialization

import aecor.core.aggregate.{AggregateCommand, CommandId}
import aecor.core.serialization.akka.{Codec, CodecSerializer, SerializationHelper}
import aecor.core.serialization.{protobuf => pb}
import akka.actor.ExtendedActorSystem
import akka.serialization.SerializationExtension
import com.google.protobuf.ByteString

import scala.util.Try

class AggregateCommandCodec(actorSystem: ExtendedActorSystem) extends Codec[AggregateCommand[AnyRef]] {
  lazy val serialization = SerializationExtension(actorSystem)
  lazy val helper = SerializationHelper(serialization)

  override def manifest(o: AggregateCommand[AnyRef]): String = ""

  override def decode(bytes: Array[Byte], manifest: String): Try[AggregateCommand[AnyRef]] =
    pb.AggregateCommand.validate(bytes).flatMap { dto =>
      serialization.deserialize(dto.payload.toByteArray, dto.serializerId, dto.manifest).map { command =>
        AggregateCommand(CommandId(dto.id), command)
      }
    }

  override def encode(o: AggregateCommand[AnyRef]): Array[Byte] = {
    import o._
    val commandRepr = helper.serialize(command)
    pb.AggregateCommand(o.id.value, commandRepr.serializerId, commandRepr.manifest, ByteString.copyFrom(commandRepr.bytes)).toByteArray
  }
}

class AggregateCommandSerializer(system: ExtendedActorSystem) extends CodecSerializer(system, new AggregateCommandCodec(system))