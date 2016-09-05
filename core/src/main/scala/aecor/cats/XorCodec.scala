package aecor.cats

import aecor.core.serialization.akka.{Codec, CodecSerializer, SerializationHelper}
import aecor.core.serialization.protobuf.SerializedObject
import akka.actor.ExtendedActorSystem
import akka.serialization.SerializationExtension
import cats.data.Xor
import cats.data.Xor.{Left, Right}

import scala.util.Try

class XorCodec(actorSystem: ExtendedActorSystem) extends Codec[Xor[AnyRef, AnyRef]] {

  val LeftManifest = "L"
  val RightManifest = "R"

  @volatile
  private var hel: SerializationHelper = _
  def helper: SerializationHelper = {
    if (hel == null) hel = SerializationHelper(SerializationExtension(actorSystem))
    hel
  }
  override def manifest(o: Xor[AnyRef, AnyRef]): String = o match {
    case Left(a) => LeftManifest
    case Right(b) => RightManifest
  }

  override def decode(bytes: Array[Byte], manifest: String): Try[Xor[AnyRef, AnyRef]] =
    SerializedObject.validate(bytes)
    .flatMap(helper.deserialize)
    .map { result =>
      manifest match {
        case LeftManifest => Xor.Left(result)
        case RightManifest => Xor.Right(result)
      }
    }

  override def encode(o: Xor[AnyRef, AnyRef]): Array[Byte] = {
    helper.serialize(o.merge).toByteArray
  }
}

class XorSerializer(extendedActorSystem: ExtendedActorSystem) extends CodecSerializer(extendedActorSystem, new XorCodec(extendedActorSystem))
