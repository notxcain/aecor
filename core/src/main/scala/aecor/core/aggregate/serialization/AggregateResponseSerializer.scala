package aecor.core.aggregate.serialization

import aecor.core.aggregate.Result.{Accepted, Rejected}
import aecor.core.aggregate.{AggregateResponse, CommandId, Result}
import aecor.core.serialization.akka.{Codec, CodecSerializer, SerializationHelper}
import aecor.core.serialization.{protobuf => pb}
import akka.actor.ExtendedActorSystem
import akka.serialization.SerializationExtension
import com.google.protobuf.ByteString

import scala.util.{Success, Try}

class AggregateResponseCodec(system: ExtendedActorSystem) extends Codec[AggregateResponse[AnyRef]] {
  lazy val serialization = SerializationExtension(system)
  lazy val helper = SerializationHelper(serialization)

  override def manifest(o: AggregateResponse[AnyRef]): String = ""

  override def decode(bytes: Array[Byte], manifest: String): Try[AggregateResponse[AnyRef]] =
    pb.AggregateResponse.validate(bytes).flatMap { dto =>
      dto.rejection.map { rejectionDto =>
         serialization
         .deserialize(rejectionDto.payload.toByteArray, rejectionDto.serializerId, rejectionDto.manifest)
         .map(Result.rejected)
      }.getOrElse(Success(Result.accepted)).map { result =>
        AggregateResponse(CommandId(dto.causedBy), result)
      }
    }

  override def encode(o: AggregateResponse[AnyRef]): Array[Byte] = {
    val rejectionDto = o.result match {
      case Accepted => Option.empty
      case Rejected(rejection) =>
        val rejectionRepr = helper.serialize(rejection)
        Some(pb.AggregateResponse.Rejection(rejectionRepr.serializerId, rejectionRepr.manifest, ByteString.copyFrom(rejectionRepr.bytes)))
    }
    pb.AggregateResponse(o.causedBy.value, rejectionDto).toByteArray
  }
}

class AggregateResponseSerializer(system: ExtendedActorSystem) extends CodecSerializer(system, new AggregateResponseCodec(system))
