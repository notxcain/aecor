package aecor.data

trait CompositeCodec[A] {
  def encode(a: A): Composite
  def decode(composite: Composite): Option[A]
}

final case class Component(value: String) extends AnyVal
final case class Composite(components: List[Component])
final case class BarId(value: String) extends AnyVal
final case class BazId(value: String) extends AnyVal
final case class FooId(barId: BarId, bazId: BazId)
object FooId {
  implicit val compositeCodec: CompositeCodec[FooId] = new CompositeCodec[FooId] {
    override def encode(a: FooId): Composite =
      Composite(Component(a.barId.value) :: Component(a.bazId.value) :: Nil)
    override def decode(composite: Composite): Option[FooId] = composite.components match {
      case barId :: bazId :: Nil => Some(FooId(BarId(barId.value), BazId(bazId.value)))
      case _                     => None
    }
  }
}
