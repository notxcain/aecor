package aecor.core.entity

object CommandContract {
  type Aux[Entity, -Command, Rejection0] = CommandContract[Entity, Command] {
    type Rejection = Rejection0
  }
  def instance[Entity, Command, Rejection0]: Aux[Entity, Command, Rejection0] = new CommandContract[Entity, Command] {
    override type Rejection = Rejection0
  }
}

trait CommandContract[Entity, -Command] {
  type Rejection
}

