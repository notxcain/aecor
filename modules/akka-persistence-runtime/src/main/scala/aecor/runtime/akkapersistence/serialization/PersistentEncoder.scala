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

  implicit val persistentReprInstance: PersistentEncoder[PersistentRepr] =
    new PersistentEncoder[PersistentRepr] {
      override def encode(a: PersistentRepr): PersistentRepr = a
    }
}
