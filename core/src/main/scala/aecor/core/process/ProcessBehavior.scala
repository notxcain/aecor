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
  implicit def ess[A](implicit ProcessBehavior: ProcessBehavior[A], ec: ExecutionContext) =
    new EventsourcedState[ProcessState[ProcessBehavior.State], ProcessStateChanged[ProcessBehavior.State]] {
      override def applyEvent(state: ProcessState[ProcessBehavior.State], event: ProcessStateChanged[ProcessBehavior.State]): ProcessState[ProcessBehavior.State] =
        state.copy(state = event.state)
      override def init: ProcessState[ProcessBehavior.State] =
        ProcessState(Set.empty, ProcessBehavior.init)
    }
}

sealed trait ProcessCommand[+E, R]
case class HandleEvent[+Event](id: EventId, event: Event) extends ProcessCommand[Event, EventHandled]
case class EventHandled(causedBy: EventId)
case class ProcessStateChanged[A](state: A, causedBy: EventId)

object ProcessEventsourcedBehavior {

  implicit def esb[A](implicit ProcessBehavior: ProcessBehavior[A], ec: ExecutionContext):
  EventsourcedBehavior.Aux[
    ProcessEventsourcedBehavior[A],
    ProcessCommand[ProcessBehavior.Event, ?],
    ProcessState[ProcessBehavior.State],
    ProcessStateChanged[ProcessBehavior.State]
    ] =
    new EventsourcedBehavior[ProcessEventsourcedBehavior[A]] {
      override type Command[X] =  ProcessCommand[ProcessBehavior.Event, X]
      override type Event = ProcessStateChanged[ProcessBehavior.State]
      override type State = ProcessState[ProcessBehavior.State]


      override def handleCommand[R](a: ProcessEventsourcedBehavior[A])(state: ProcessState[ProcessBehavior.State], command: ProcessCommand[ProcessBehavior.Event, R]) =
        command match {
          case HandleEvent(id, event) =>
            (if (state.processedEvents.contains(id)) {
              Future.successful(Seq.empty)
            } else {
              ProcessBehavior.handleEvent(a.a)(state.state, event)
              .map(_.map(ProcessStateChanged(_, id)).toList)
            }).map(events => EventHandled(id) -> events)
        }
    }
}
