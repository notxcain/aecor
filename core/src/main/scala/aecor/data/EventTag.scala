package aecor.data

final case class EventTag[E](value: String) extends AnyVal {
  def modify(f: String => String): EventTag[E] = copy(value = f(value))
}
