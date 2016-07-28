package aecor.core.process

import aecor.core.actor.NowOrDeferred.Now
import aecor.core.actor.{EventsourcedBehavior, NowOrDeferred}
import aecor.core.aggregate._

import scala.collection.immutable.Seq

trait ProcessBehavior[A] {
  type Event
  type State
  def withState(a: A)(state: State): A
  def handleEvent(a: A)(event: Event): NowOrDeferred[State]
}

object ProcessBehavior {
  type Aux[A, Event0, State0] = ProcessBehavior[A] {
    type Event = Event0
    type State = State0
  }
}

case class ProcessEventsourcedBehavior[A](a: A, processedEvents: Set[EventId])

case class HandleEvent[+A](id: EventId, event: A)
case class EventHandled(causedBy: EventId)
case class ProcessStateChanged[A](state: A, causedBy: EventId)

object ProcessEventsourcedBehavior {
  implicit def esb[A](implicit ProcessBehavior: ProcessBehavior[A]): EventsourcedBehavior.Aux[ProcessEventsourcedBehavior[A], HandleEvent[ProcessBehavior.Event], EventHandled, ProcessStateChanged[ProcessBehavior.State]] =
    new EventsourcedBehavior[ProcessEventsourcedBehavior[A]] {
      override type Command = HandleEvent[ProcessBehavior.Event]
      override type Response = EventHandled
      override type Event = ProcessStateChanged[ProcessBehavior.State]

      override def handleCommand(a: ProcessEventsourcedBehavior[A])(command: HandleEvent[ProcessBehavior.Event]): NowOrDeferred[(EventHandled, Seq[ProcessStateChanged[ProcessBehavior.State]])] =
        (if (a.processedEvents.contains(command.id)) {
          Now(Seq.empty)
        } else {
          ProcessBehavior.handleEvent(a.a)(command.event).map { state =>
            Seq(ProcessStateChanged(state, command.id))
          }
        }).map(events => EventHandled(command.id) -> events)

      override def applyEvent(a: ProcessEventsourcedBehavior[A])(event: ProcessStateChanged[ProcessBehavior.State]): ProcessEventsourcedBehavior[A] =
        a.copy(
          a = ProcessBehavior.withState(a.a)(event.state),
          processedEvents = a.processedEvents + event.causedBy
        )
    }
}
