package aecor.core.serialization.akka

import aecor.core.serialization.protobuf.SerializedObject
import akka.persistence.PersistentRepr
import akka.serialization.{Serialization, SerializerWithStringManifest}
import com.google.protobuf.ByteString

import scala.util.Try

class SerializationHelper(serialization: Serialization) {
  def serialize(a: AnyRef): SerializedObject = {
    val serializer = serialization.findSerializerFor(a)
    val serManifest = serializer match {
      case ser2: SerializerWithStringManifest ⇒
        ser2.manifest(a)
      case _ ⇒
        if (serializer.includeManifest) a.getClass.getName
        else PersistentRepr.Undefined
    }
    SerializedObject(serializer.identifier, serManifest, ByteString.copyFrom(serializer.toBinary(a)))
  }
  def deserialize(repr: SerializedObject): Try[AnyRef] = {
    serialization.deserialize(repr.payload.toByteArray, repr.serializerId, repr.manifest)
  }
}

object SerializationHelper {
  def apply(serialization: Serialization): SerializationHelper = new SerializationHelper(serialization)
}
