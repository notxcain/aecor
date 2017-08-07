package aecor.runtime.akkapersistence.serialization

trait PersistentEncoder[A] {
  def encode(a: A): PersistentRepr
}

object PersistentEncoder {
  def apply[A](implicit instance: PersistentEncoder[A]): PersistentEncoder[A] = instance

  def instance[A](f: A => PersistentRepr): PersistentEncoder[A] =
    new PersistentEncoder[A] {
      override def encode(a: A) = f(a)
    }

  implicit def fromCodec[A](implicit codec: Codec[A]): PersistentEncoder[A] =
    new PersistentEncoder[A] {
      override def encode(a: A) =
        PersistentRepr(codec.manifest(a), codec.encode(a))
    }
}
