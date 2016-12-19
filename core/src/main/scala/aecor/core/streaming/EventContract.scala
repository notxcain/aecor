package aecor.core.streaming

import aecor.core.aggregate.AggregateBehavior

trait EventContract[Aggregate] {
  type Event
}

object EventContract {
  type Aux[Aggregate, Event0] = EventContract[Aggregate] {
    type Event = Event0
  }
  def instance[Entity, Event0]: Aux[Entity, Event0] =
    new EventContract[Entity] {
      type Event = Event0
    }
  implicit def fromBehavior[A, C[_]](
      implicit A: AggregateBehavior[A]): Aux[A, A.Event] = instance
}
