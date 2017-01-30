package aecor.aggregate

sealed trait Tagging[A] {
  def apply(e: A): Set[String]
}

object Tagging {

  def apply[A](tag1: String): Tagging[A] =
    new Tagging[A] {
      override def apply(e: A): Set[String] = Set(tag1)
    }

  def apply[A](tag1: A => String): Tagging[A] =
    new Tagging[A] {
      override def apply(e: A): Set[String] = Set(tag1(e))
    }

  def apply[A](tag1: String, tag2: String): Tagging[A] =
    new Tagging[A] {
      override def apply(e: A): Set[String] = Set(tag1, tag2)
    }

  def apply[A](tag1: A => String, tag2: String): Tagging[A] =
    new Tagging[A] {
      override def apply(e: A): Set[String] = Set(tag1(e), tag2)
    }
}
