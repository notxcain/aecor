package aecor.aggregate

import aecor.data.EventTag

sealed trait Tagging[A] {
  def apply(e: A): Set[String]
}

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
