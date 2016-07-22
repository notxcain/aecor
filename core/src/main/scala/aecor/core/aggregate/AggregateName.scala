package aecor.core.aggregate

object AggregateName {
  def apply[A](implicit instance: AggregateName[A]): AggregateName[A] = instance
  def instance[A](x: String): AggregateName[A] = new AggregateName[A] {
    override def value: String = x
  }
}

trait AggregateName[A] {
  def value: String
}
