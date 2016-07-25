package aecor.core.aggregate

object CommandContract {
  type Aux[Entity, -Command, Rejection0] = CommandContract[Entity, Command] {
    type Rejection = Rejection0
  }
  def instance[Entity, Command, Rejection0]: Aux[Entity, Command, Rejection0] = new CommandContract[Entity, Command] {
    override type Rejection = Rejection0
  }

  implicit def fromBehavior[A](implicit A: AggregateBehavior[A]): Aux[A, A.Cmd, A.Rjn] = instance
}

trait CommandContract[Entity, -Command] {
  type Rejection
}