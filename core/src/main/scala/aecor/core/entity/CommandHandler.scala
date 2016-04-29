package aecor.core.entity

object CommandHandler {
  def instance[Entity, State, Command, Event0, Rejection](f: State => Command => CommandHandlerResult[Event0, Rejection]): CommandHandler.Aux[Entity, State, Command, Event0, Rejection] =
    new CommandHandler[Entity, State, Command, Rejection] {
      override type Event = Event0
      override def apply(state: State, command: Command): CommandHandlerResult[Event, Rejection] = f(state)(command)
    }
  type Aux[Entity, State, Command, Event0, Rejection] = CommandHandler[Entity, State, Command, Rejection] {
    type Event = Event0
  }
}

trait CommandHandler[Entity, State, Command, Rejection] {
  type Event
  def apply(state: State, command: Command): CommandHandlerResult[Event, Rejection]
}
