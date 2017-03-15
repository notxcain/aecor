package aecor.aggregate

import aecor.data.EventTag

sealed abstract class Tagging[A] extends Product with Serializable {
  def apply(e: A): Set[String]
}

/**
  * Please refer to akka-persistence-cassandra documentation and its reference.conf
  * to understand how tagging works internally
  */
object Tagging {

  def apply[A](tag1: EventTag[A]): Tagging[A] =
    new Tagging[A] {
      override def apply(e: A): Set[String] = Set(tag1.value)
    }

  def apply[A](tag1: A => EventTag[A]): Tagging[A] =
    new Tagging[A] {
      override def apply(e: A): Set[String] = Set(tag1(e).value)
    }

  def apply[A](tag1: EventTag[A], tag2: EventTag[A]): Tagging[A] =
    new Tagging[A] {
      override def apply(e: A): Set[String] = Set(tag1.value, tag2.value)
    }

  def apply[A](tag1: A => EventTag[A], tag2: EventTag[A]): Tagging[A] =
    new Tagging[A] {
      override def apply(e: A): Set[String] = Set(tag1(e).value, tag2.value)
    }

  def apply[A](tag1: EventTag[A], tag2: EventTag[A], tag3: EventTag[A]): Tagging[A] =
    new Tagging[A] {
      override def apply(e: A): Set[String] = Set(tag1.value, tag2.value, tag3.value)
    }

  def apply[A](tag1: A => EventTag[A], tag2: EventTag[A], tag3: EventTag[A]): Tagging[A] =
    new Tagging[A] {
      override def apply(e: A): Set[String] = Set(tag1(e).value, tag2.value, tag3.value)
    }
}
