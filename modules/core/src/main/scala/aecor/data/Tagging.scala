package aecor.data

sealed abstract class Tagging[-A] {
  def tag(a: A): Set[EventTag]
  def tags: List[EventTag]
}

object Tagging {
  final case class Partitioned[-A](numberOfPartitions: Int, tag: EventTag) extends Tagging[A] {
    private def tagForPartition(partition: Int) = EventTag(s"${tag.value}$partition")
    def tags: List[EventTag] = (0 until numberOfPartitions).map(tagForPartition).toList
    override def tag(a: A): Set[EventTag] =
      Set(tags(scala.math.abs(a.hashCode % numberOfPartitions)))
  }
  final case class Const[-A](tag: EventTag) extends Tagging[A] {
    override def tag(a: A): Set[EventTag] = Set(tag)
    override def tags: List[EventTag] = List(tag)
  }

  def const[A](tag: EventTag): Const[A] = Const(tag)

  def partitioned[I](numberOfPartitions: Int)(tag: EventTag): Partitioned[I] =
    Partitioned(numberOfPartitions, tag)

}
