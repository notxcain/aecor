package aecor.aggregate.serialization

import akka.actor.ExtendedActorSystem
import akka.serialization.{ BaseSerializer, SerializerWithStringManifest }

final case class PersistentRepr(manifest: String, payload: Array[Byte])

class PersistentReprSerializer(val system: ExtendedActorSystem)
    extends SerializerWithStringManifest
    with BaseSerializer {

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case pr: PersistentRepr => pr.payload
    case other =>
      throw new IllegalArgumentException(s"Expected object of type PersistenRepr, got [$other]")
  }

  override def manifest(o: AnyRef): String = o match {
    case pr: PersistentRepr => pr.manifest
    case other =>
      throw new IllegalArgumentException(s"Expected object of type PersistenRepr, got [$other]")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    PersistentRepr(manifest, bytes)
}
