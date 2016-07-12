package aecor.core.serialization.akka

import akka.actor.ExtendedActorSystem
import akka.serialization.{BaseSerializer, SerializerWithStringManifest}

import scala.reflect.ClassTag
import scala.util.Try

trait Codec[T] {
  def manifest(o: T): String
  def fromBinary(bytes: Array[Byte], manifest: String): Option[Try[T]]
  def toBinary(o: T): Array[Byte]
}

abstract class CodecSerializer[A <: AnyRef : ClassTag](val system: ExtendedActorSystem, codec: Codec[A]) extends SerializerWithStringManifest with BaseSerializer {

  override def manifest(o: AnyRef): String = o match {
    case a: A => codec.manifest(a)
    case _ ⇒
      throw new IllegalArgumentException(s"Can't serialize object of type ${o.getClass} in [${getClass.getName}]")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    codec.fromBinary(bytes, manifest).map {
      case scala.util.Success(x) => x
      case scala.util.Failure(throwable) => throw new IllegalArgumentException(s"Can't deserialize object of type $manifest in [${getClass.getName}]", throwable)
    }.getOrElse {
      throw new IllegalArgumentException(s"Can't deserialize object of type $manifest in [${getClass.getName}]")
    }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case a: A => codec.toBinary(a)
    case _ =>
      throw new IllegalArgumentException(s"Can't serialize object of type ${o.getClass} in [${getClass.getName}]")
  }
}

