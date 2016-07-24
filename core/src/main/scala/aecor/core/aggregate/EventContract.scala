package aecor.core.aggregate

trait EventContract[Aggregate] {
  type Event
}

object EventContract {
  type Aux[Aggregate, Event0] = EventContract[Aggregate] {
    type Event = Event0
  }
  def instance[Entity, Event0]: Aux[Entity, Event0] = new EventContract[Entity] {
    type Event = Event0
  }
}
