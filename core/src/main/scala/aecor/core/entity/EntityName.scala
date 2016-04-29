package aecor.core.entity

object EntityName {
  def apply[A](implicit instance: EntityName[A]): EntityName[A] = instance
  def instance[A](x: String): EntityName[A] = new EntityName[A] {
    override def value: String = x
  }
}

trait EntityName[A] {
  def value: String
}
