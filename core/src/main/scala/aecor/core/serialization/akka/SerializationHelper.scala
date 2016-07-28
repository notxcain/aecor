package aecor.core.serialization.akka

import akka.persistence.PersistentRepr
import akka.serialization.{Serialization, SerializerWithStringManifest}

case class SerializedRepr(serializerId: Int, manifest: String, bytes: Array[Byte])

class SerializationHelper(serialization: Serialization) {
  def serialize(a: AnyRef): SerializedRepr = {
    val serializer = serialization.findSerializerFor(a)
    val serManifest = serializer match {
      case ser2: SerializerWithStringManifest ⇒
        ser2.manifest(a)
      case _ ⇒
        if (serializer.includeManifest) a.getClass.getName
        else PersistentRepr.Undefined
    }
    SerializedRepr(serializer.identifier, serManifest, serializer.toBinary(a))
  }
}

object SerializationHelper {
  def apply(serialization: Serialization): SerializationHelper = new SerializationHelper(serialization)
}
