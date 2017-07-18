package aecor.data

sealed abstract class Tagging[A] {
  def apply(e: A): Set[EventTag]
}

object Tagging {

  def const[A](tag: EventTag): Tagging[A] =
    new Tagging[A] {
      override def apply(e: A): Set[EventTag] = Set(tag)
    }

  def dynamic[A](f: A => EventTag): Tagging[A] =
    new Tagging[A] {
      override def apply(e: A): Set[EventTag] = Set(f(e))
    }

  def partitioned[A](numberOfPartitions: Int,
                     tag: EventTag)(partitionKey: A => String): Tagging[A] =
    Tagging.dynamic[A] { a =>
      val partition = scala.math.abs(partitionKey(a).hashCode % numberOfPartitions)
      EventTag(s"${tag.value}$partition")
    }
}
