package aecor.core.aggregate

object ApplyEvent {
  def apply[State, Event](state: State, event: Event)(f: State => Event => State) =
    f(state)(event)
}
