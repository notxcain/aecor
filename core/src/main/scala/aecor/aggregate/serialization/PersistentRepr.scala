package aecor.aggregate.serialization

import akka.actor.ExtendedActorSystem
import akka.serialization.{ BaseSerializer, SerializerWithStringManifest }

final case class PersistentRepr(manifest: String, payload: Array[Byte])

class PersistentReprSerializer(val system: ExtendedActorSystem)
    extends SerializerWithStringManifest
    with BaseSerializer {

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case pr: PersistentRepr => pr.payload
  }

  override def manifest(o: AnyRef): String = o match {
    case pr: PersistentRepr => pr.manifest
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    PersistentRepr(manifest, bytes)
}
