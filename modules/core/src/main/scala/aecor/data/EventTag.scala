package aecor.data
import cats.Order

final case class EventTag(value: String) extends AnyVal
object EventTag {
  implicit val orderInstance: Order[EventTag] = Order.fromOrdering(Ordering[String].on(_.value))
}
