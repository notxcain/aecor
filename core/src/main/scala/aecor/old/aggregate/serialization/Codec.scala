package aecor.old.aggregate.serialization

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
