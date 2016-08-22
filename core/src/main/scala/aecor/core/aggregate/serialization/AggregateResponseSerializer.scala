package aecor.core.aggregate.serialization

import aecor.core.aggregate.AggregateResponse
import aecor.core.aggregate.AggregateResponse.{Accepted, Rejected}
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
    if (bytes.isEmpty) {
      Success(AggregateResponse.accepted)
    } else {
      pb.Rejection.validate(bytes).flatMap { rejectionDto =>
        serialization
        .deserialize(rejectionDto.payload.toByteArray, rejectionDto.serializerId, rejectionDto.manifest)
        .map(AggregateResponse.rejected)
      }
    }

  override def encode(o: AggregateResponse[AnyRef]): Array[Byte] = {
    o match {
      case Accepted => Array.empty[Byte]
      case Rejected(rejection) =>
        val rejectionRepr = helper.serialize(rejection)
        pb.Rejection(rejectionRepr.serializerId, rejectionRepr.manifest, ByteString.copyFrom(rejectionRepr.bytes)).toByteArray
    }
  }
}

class AggregateResponseSerializer(system: ExtendedActorSystem) extends CodecSerializer(system, new AggregateResponseCodec(system))
