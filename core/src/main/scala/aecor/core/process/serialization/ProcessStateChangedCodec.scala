package aecor.core.process.serialization

import aecor.core.aggregate.EventId
import aecor.core.process.ProcessStateChanged
import aecor.core.serialization.akka.{Codec, SerializationHelper, SerializedRepr}
import aecor.core.serialization.{protobuf => pb}
import akka.actor.ExtendedActorSystem
import akka.serialization.SerializationExtension
import com.google.protobuf.ByteString

import scala.util.Try

class ProcessStateChangedCodec(actorSystem: ExtendedActorSystem) extends Codec[ProcessStateChanged[AnyRef]] {

  @volatile
  private var hel: SerializationHelper = _
  def helper: SerializationHelper = {
    if (hel == null) hel = SerializationHelper(SerializationExtension(actorSystem))
    hel
  }

  override def manifest(o: ProcessStateChanged[AnyRef]): String = ""

  override def decode(bytes: Array[Byte], manifest: String): Try[ProcessStateChanged[AnyRef]] =
    for {
      pb.ProcessStateChanged(causedBy, pb.SerializedObject(serializerId, manifest, bytes)) <- pb.ProcessStateChanged.validate(bytes)
      state <- helper.deserialize(SerializedRepr(serializerId, manifest, bytes.toByteArray))
    } yield {
      ProcessStateChanged(state, EventId(causedBy))
    }

  override def encode(o: ProcessStateChanged[AnyRef]): Array[Byte] = {
    val SerializedRepr(serializerId, manifest, bytes) = helper.serialize(o.state)
    pb.ProcessStateChanged(o.causedBy.value, pb.SerializedObject(serializerId, manifest, ByteString.copyFrom(bytes))).toByteArray
  }
}
