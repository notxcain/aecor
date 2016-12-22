package aecor.aggregate

sealed trait EventTagger[E] {
  def apply(e: E): String
}

object EventTagger {
  def const[E](value: String): EventTagger[E] =
    new EventTagger[E] {
      override def apply(e: E): String = value
    }
}
