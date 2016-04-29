package aecor.example.domain

case class Amount(value: Long) extends AnyVal {
  def >(other: Amount): Boolean = value > other.value
  def <=(other: Amount): Boolean = value <= other.value
  def -(other: Amount): Amount = Amount(value - other.value)
  def +(other: Amount): Amount = Amount(value + other.value)
}
