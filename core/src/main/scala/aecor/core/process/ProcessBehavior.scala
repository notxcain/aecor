package aecor.core.process

import aecor.core.actor.{EventsourcedBehavior, EventsourcedState}
import aecor.core.aggregate._

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}

trait ProcessBehavior[A] {
  type Event
  type State
  def init: State
  def handleEvent(a: A)(state: State, event: Event): Future[Option[State]]
}

object ProcessBehavior {
  type Aux[A, Event0, State0] = ProcessBehavior[A] {
    type Event = Event0
    type State = State0
  }
}

case class ProcessEventsourcedBehavior[A](a: A)

case class ProcessState[A](processedEvents: Set[EventId], state: A)

object ProcessState {
  implicit def ess[A, Event, State]
  (implicit A: ProcessBehavior.Aux[A, Event, State])
  : EventsourcedState[ProcessState[State], ProcessStateChanged[State]] =
    new EventsourcedState[ProcessState[State], ProcessStateChanged[State]] {
      override def applyEvent(state: ProcessState[State], event: ProcessStateChanged[State]): ProcessState[State] =
        state.copy(state = event.state)
      override def init: ProcessState[State] =
        ProcessState(Set.empty, A.init)
    }
}

sealed trait ProcessCommand[+E, R]

case class HandleEvent[+Event](id: EventId, event: Event) extends ProcessCommand[Event, EventHandled]

case class EventHandled(causedBy: EventId)

case class ProcessStateChanged[A](state: A, causedBy: EventId)

object ProcessEventsourcedBehavior {

  implicit def esb[A, Event0, State0](implicit A: ProcessBehavior.Aux[A, Event0, State0], ec: ExecutionContext):
  EventsourcedBehavior.Aux[
    ProcessEventsourcedBehavior[A],
    ProcessCommand[Event0, ?],
    ProcessState[State0],
    ProcessStateChanged[State0]
    ] =
    new EventsourcedBehavior[ProcessEventsourcedBehavior[A]] {
      override type Command[X] =  ProcessCommand[Event0, X]
      override type Event = ProcessStateChanged[State0]
      override type State = ProcessState[State0]


      override def handleCommand[R](a: ProcessEventsourcedBehavior[A])(state: State, command: Command[R]) =
        command match {
          case HandleEvent(id, event) =>
            (if (state.processedEvents.contains(id)) {
              Future.successful(Seq.empty)
            } else {
              A.handleEvent(a.a)(state.state, event)
              .map(_.map(ProcessStateChanged(_, id)).toList)
            }).map(events => EventHandled(id) -> events)
        }
    }
}
