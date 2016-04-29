package aecor.core.entity

object EventProjector {
  def instance[Entity, State, Event](f: State => Event => State): EventProjector[Entity, State, Event] = new EventProjector[Entity, State, Event] {
    override def apply(state: State, event: Event): State = f(state)(event)
  }
}

trait EventProjector[Entity, State, Event] {
  def apply(state: State, event: Event): State
}
