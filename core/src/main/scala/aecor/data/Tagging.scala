package aecor.data

sealed abstract class Tagging[A] {
  def apply(e: A): Set[EventTag[A]]
}

/**
  * Please refer to akka-persistence-cassandra documentation and its reference.conf
  * to understand how tagging works internally
  */
object Tagging {

  def const[A](tag1: EventTag[A]): Tagging[A] =
    new Tagging[A] {
      override def apply(e: A): Set[EventTag[A]] = Set(tag1)
    }

  def dynamic[A](tag1: A => EventTag[A]): Tagging[A] =
    new Tagging[A] {
      override def apply(e: A): Set[EventTag[A]] = Set(tag1(e))
    }

  def partitioned[A](numberOfPartitions: Int,
                     tag: EventTag[A])(partitionKey: A => String): Tagging[A] =
    Tagging.dynamic[A] { a =>
      val partition = scala.math.abs(partitionKey(a).hashCode % numberOfPartitions)
      EventTag[A](s"${tag.value}$partition")
    }
}
