package aecor.data
import scala.collection.immutable._

sealed abstract class Tagging[-A] {
  def tag(a: A): Set[EventTag]
}

object Tagging {
  final case class Partitioned[-A](numberOfPartitions: Int, tag: EventTag) extends Tagging[A] {
    private def tagForPartition(partition: Int) = EventTag(s"${tag.value}$partition")
    def tags: Seq[EventTag] = (0 to numberOfPartitions).map(tagForPartition)
    override def tag(a: A): Set[EventTag] =
      Set(tags(a.hashCode % numberOfPartitions))
  }
  final case class Const[-A](tag: EventTag) extends Tagging[A] {
    override def tag(a: A): Set[EventTag] = Set(tag)
  }

  def const[A](tag: EventTag): Const[A] = Const(tag)

  def partitioned[I](numberOfPartitions: Int)(tag: EventTag): Partitioned[I] =
    Partitioned(numberOfPartitions, tag)

}
