package aecor.core.entity

object EntityBehavior {
  type Aux[Entity, State0, Command0, Event0, Rejection0] = EntityBehavior[Entity] {
    type State = State0
    type Command = Command0
    type Event = Event0
    type Rejection = Rejection0
  }
  def instance[Entity, State0, Command0, Event0, Rejection0](initialState0: State0, commandHandler0: CommandHandler.Aux[State0, Command0, Event0, Rejection0], eventProjector0: EventProjector[State0, Event0]): Aux[Entity, State0, Command0, Event0, Rejection0] =
    new EntityBehavior[Entity] {
      override type State = State0

      override def commandHandler = commandHandler0

      override def eventProjector = eventProjector0

      override def initialState = initialState0

      override type Command = Command0
      override type Rejection = Rejection0
      override type Event = Event0
    }
}

trait EntityBehavior[Entity] {
  type State
  type Command
  type Event
  type Rejection
  def initialState: State
  def commandHandler: CommandHandler.Aux[State, Command, Event, Rejection]
  def eventProjector: EventProjector[State, Event]
}