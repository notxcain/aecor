package aecor.data
import scala.collection.immutable._
sealed abstract class Tagging[A] {
  def apply(e: A): Set[EventTag]
}

object Tagging {
  sealed abstract class Partitioned[A] extends Tagging[A] {
    def tags: Seq[EventTag]
  }
  sealed abstract class Const[A] extends Tagging[A] {
    def tag: EventTag
    final override def apply(e: A): Set[EventTag] = Set(tag)
  }

  def const[A](tag: EventTag): Const[A] = {
    val tag0 = tag
    new Const[A] {
      override val tag: EventTag = tag0
    }
  }

  def dynamic[A](f: A => EventTag): Tagging[A] =
    new Tagging[A] {
      override def apply(e: A): Set[EventTag] = Set(f(e))
    }

  def partitioned[A](numberOfPartitions: Int,
                     tag: EventTag)(partitionKey: A => String): Partitioned[A] =
    new Partitioned[A] {
      private def tagForPartition(partition: Int) = EventTag(s"${tag.value}$partition")
      override def tags: Seq[EventTag] = (0 to numberOfPartitions).map(tagForPartition)
      override def apply(a: A): Set[EventTag] = {
        val partition = scala.math.abs(partitionKey(a).hashCode % numberOfPartitions)
        Set(tagForPartition(partition))
      }
    }

}
