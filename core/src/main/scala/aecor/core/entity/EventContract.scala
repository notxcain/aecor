package aecor.core.entity

trait EventContract[Entity] {
  type Event
}

object EventContract {
  type Aux[Entity, Event0] = EventContract[Entity] {
    type Event = Event0
  }
  def instance[Entity, Event0]: Aux[Entity, Event0] = new EventContract[Entity] {
    type Event = Event0
  }
}
