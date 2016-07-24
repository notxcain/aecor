package aecor.core.aggregate

object EventProjector {
  def instance[State, Event](f: State => Event => State): EventProjector[State, Event] = new EventProjector[State, Event] {
    override def apply(state: State, event: Event): State = f(state)(event)
  }
  def apply[State, Event](state: State, event: Event)(f: State => Event => State) =
    f(state)(event)
}

trait EventProjector[State, Event] {
  def apply(state: State, event: Event): State
}
