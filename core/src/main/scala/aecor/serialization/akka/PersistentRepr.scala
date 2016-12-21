package aecor.serialization.akka
import akka.actor.ExtendedActorSystem

import scala.util.{ Success, Try }

final case class PersistentRepr(manifest: String, payload: Array[Byte])

object PersistentRepr {
  implicit def fromTuple(a: (String, Array[Byte])): PersistentRepr =
    PersistentRepr(a._1, a._2)
}

object PersistentReprCodec extends Codec[PersistentRepr] {
  override def manifest(o: PersistentRepr): String = o.manifest

  override def decode(bytes: Array[Byte], manifest: String): Try[PersistentRepr] =
    Success(PersistentRepr(manifest, bytes))

  override def encode(o: PersistentRepr): Array[Byte] = o.payload
}

class PersistentReprSerializer(extendedActorSystem: ExtendedActorSystem)
    extends CodecSerializer(extendedActorSystem, PersistentReprCodec)
