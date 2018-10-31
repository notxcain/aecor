package aecor.example.common

final case class Amount(value: BigDecimal) extends AnyVal {
  def >(other: Amount): Boolean = value > other.value
  def <=(other: Amount): Boolean = value <= other.value
  def >=(other: Amount): Boolean = value >= other.value
  def -(other: Amount): Amount = Amount(value - other.value)
  def +(other: Amount): Amount = Amount(value + other.value)
}

object Amount {
  val zero: Amount = Amount(0)
}
