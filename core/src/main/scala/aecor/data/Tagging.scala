package aecor.data

sealed abstract class Tagging[A] {
  def apply(e: A): Set[EventTag[A]]
}

/**
  * Please refer to akka-persistence-cassandra documentation and its reference.conf
  * to understand how tagging works internally
  */
object Tagging {

  def apply[A](tag1: EventTag[A]): Tagging[A] =
    new Tagging[A] {
      override def apply(e: A): Set[EventTag[A]] = Set(tag1)
    }

  def apply[A](tag1: A => EventTag[A]): Tagging[A] =
    new Tagging[A] {
      override def apply(e: A): Set[EventTag[A]] = Set(tag1(e))
    }

  def apply[A](tag1: EventTag[A], tag2: EventTag[A]): Tagging[A] =
    new Tagging[A] {
      override def apply(e: A): Set[EventTag[A]] = Set(tag1, tag2)
    }

  def apply[A](tag1: A => EventTag[A], tag2: EventTag[A]): Tagging[A] =
    new Tagging[A] {
      override def apply(e: A): Set[EventTag[A]] = Set(tag1(e), tag2)
    }

  def apply[A](tag1: EventTag[A], tag2: EventTag[A], tag3: EventTag[A]): Tagging[A] =
    new Tagging[A] {
      override def apply(e: A): Set[EventTag[A]] = Set(tag1, tag2, tag3)
    }

  def apply[A](tag1: A => EventTag[A], tag2: EventTag[A], tag3: EventTag[A]): Tagging[A] =
    new Tagging[A] {
      override def apply(e: A): Set[EventTag[A]] = Set(tag1(e), tag2, tag3)
    }
}
