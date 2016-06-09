package aecor.core.entity

object CommandHandler {
  def instance[State, Command, Event, Rejection](f: State => Command => CommandHandlerResult[Event, Rejection]): CommandHandler.Aux[State, Command, Event, Rejection] =
    new CommandHandler[State, Command, Event, Rejection] {
      override def apply(state: State, command: Command): CommandHandlerResult[Event, Rejection] = f(state)(command)
    }
  type Aux[State, Command, Event, Rejection] = CommandHandler[State, Command, Event, Rejection]

  def withEnvelope[Envelope] = new WithEnvelope[Envelope] {}

  trait WithEnvelope[Envelope] {
    def apply[State, Event, Rejection, Id, Command](extract: Envelope => (Id, Command))(f: Id => State => Command => CommandHandlerResult[Event, Rejection]): CommandHandler.Aux[State, Envelope, Event, Rejection] =
      new CommandHandler[State, Envelope, Event, Rejection] {
        override def apply(state: State, envelope: Envelope): CommandHandlerResult[Event, Rejection] = {
          val (id, command) = extract(envelope)
          f(id)(state)(command)
        }
      }
  }
}

trait CommandHandler[-State, -Command, +Event, +Rejection] {
  def apply(state: State, command: Command): CommandHandlerResult[Event, Rejection]
}