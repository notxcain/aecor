package aecor.core.entity

object EventProjector {
  def instance[State, Event](f: State => Event => State): EventProjector[State, Event] = new EventProjector[State, Event] {
    override def apply(state: State, event: Event): State = f(state)(event)
  }
}

trait EventProjector[State, Event] {
  def apply(state: State, event: Event): State
}
