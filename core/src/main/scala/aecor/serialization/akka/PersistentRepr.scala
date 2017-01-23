package aecor.serialization.akka
import akka.actor.ExtendedActorSystem
import akka.serialization.{ BaseSerializer, SerializerWithStringManifest }

final case class PersistentRepr(manifest: String, payload: Array[Byte])

object PersistentRepr {
  implicit def fromTuple(a: (String, Array[Byte])): PersistentRepr =
    PersistentRepr(a._1, a._2)
}

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
