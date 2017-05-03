package aecor.data

sealed abstract class Tagging[A] {
  def apply(e: A): Set[EventTag[A]]
}

object Tagging {

  def const[A](tag1: EventTag[A]): Tagging[A] =
    new Tagging[A] {
      override def apply(e: A): Set[EventTag[A]] = Set(tag1)
    }

  def dynamic[A](f: A => EventTag[A]): Tagging[A] =
    new Tagging[A] {
      override def apply(e: A): Set[EventTag[A]] = Set(f(e))
    }

  def partitioned[A](numberOfPartitions: Int,
                     tag: EventTag[A])(partitionKey: A => String): Tagging[A] =
    Tagging.dynamic[A] { a =>
      val partition = scala.math.abs(partitionKey(a).hashCode % numberOfPartitions)
      EventTag[A](s"${tag.value}$partition")
    }
}
