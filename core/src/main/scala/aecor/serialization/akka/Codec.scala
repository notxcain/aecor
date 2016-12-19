package aecor.serialization.akka

import akka.actor.ExtendedActorSystem
import akka.serialization.{ BaseSerializer, SerializerWithStringManifest }

import scala.reflect.ClassTag
import scala.util.Try

/**
  * Used to make creation of [akka.serialization.Serializer] less error prone
  *
  * @tparam T - a type of object being serialized
  */
trait Codec[T] {
  def manifest(o: T): String
  def decode(bytes: Array[Byte], manifest: String): Try[T]
  def encode(o: T): Array[Byte]
}

/**
  * Makes creation of [akka.serialization.Serializer] less error prone
  * by delegating ser/de to type [Codec]
  *
  * User typically creates a subclass providing codec intance
  *
  * @param system an actor system
  * @param codec an instance of [Codec] to use
  * @tparam A - type of serialized object
  */
class CodecSerializer[A <: AnyRef: ClassTag](val system: ExtendedActorSystem, codec: Codec[A])
    extends SerializerWithStringManifest
    with BaseSerializer {

  final override def manifest(o: AnyRef): String = o match {
    case a: A => codec.manifest(a)
    case _ ⇒
      throw new IllegalArgumentException(
        s"Can't serialize object of type ${o.getClass} in [${getClass.getName}]"
      )
  }

  final override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    codec.decode(bytes, manifest) match {
      case scala.util.Success(x) => x
      case scala.util.Failure(throwable) =>
        throw new IllegalArgumentException(
          s"Can't deserialize object of type $manifest in [${getClass.getName}]",
          throwable
        )
    }

  final override def toBinary(o: AnyRef): Array[Byte] = o match {
    case a: A => codec.encode(a)
    case _ =>
      throw new IllegalArgumentException(
        s"Can't serialize object of type ${o.getClass} in [${getClass.getName}]"
      )
  }
}
