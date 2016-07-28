package aecor.core.aggregate

import java.time.Instant

import aecor.core.actor.{EventsourcedBehavior, NowOrDeferred}
import aecor.core.actor.NowOrDeferred.{Deferred, Now}
import aecor.core.message.Correlation
import aecor.util._

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}

case class CommandId(value: String) extends AnyVal

case class EventId(value: String) extends AnyVal

case class AggregateCommand[+A](id: CommandId, command: A)

object AggregateCommand {
  implicit def correlation[A](implicit A: Correlation[A]): Correlation[AggregateCommand[A]] = Correlation.instance(c => A(c.command))
}

case class AggregateEvent[+Event](id: EventId, event: Event, timestamp: Instant, causedBy: CommandId)

case class AggregateResponse[+Rejection](causedBy: CommandId, result: Result[Rejection])

sealed trait Result[+Rejection]

object Result {
  def accepted[R]: Result[R] = Accepted
  def rejected[R](rejection: R): Result[R] = Rejected(rejection)

  case object Accepted extends Result[Nothing]

  case class Rejected[+Rejection](rejection: Rejection) extends Result[Rejection]

}

sealed trait AggregateDecision[+R, +E]
case class Accept[+E](events: Seq[E]) extends AggregateDecision[Nothing, E]
case class Reject[+R](rejection: R) extends AggregateDecision[R, Nothing]

trait AggregateBehavior[A] {
  type Cmd
  type Evt
  type Rjn
  def handleCommand(a: A)(command: Cmd): NowOrDeferred[AggregateDecision[Rjn, Evt]]
  def applyEvent(a: A)(e: Evt): A
}

object AggregateBehavior {
  object syntax {
    def accept[R, E](events: E*): Now[AggregateDecision[R, E]] = Now(Accept(events.toVector))
    def reject[R, E](rejection: R): Now[AggregateDecision[R, E]] = Now(Reject(rejection))
    def defer[R, E](f: ExecutionContext => Future[AggregateDecision[R, E]]): NowOrDeferred[AggregateDecision[R, E]] = Deferred(f)

    implicit def fromNow[A](now: Now[A]): A = now.value

    def handle[State, In, Out](state: State, in: In)(f: State => In => Out): Out = f(state)(in)
  }
  type Aux[A, Command0, Rejection0, Event0] = AggregateBehavior[A] {
    type Cmd = Command0
    type Evt = Event0
    type Rjn = Rejection0
  }
}

case class AggregateEventsourcedBehavior[Aggregate](aggregate: Aggregate, processedCommands: Set[CommandId])

object AggregateEventsourcedBehavior {
  implicit def instance[A](implicit A: AggregateBehavior[A]): EventsourcedBehavior.Aux[AggregateEventsourcedBehavior[A], AggregateCommand[A.Cmd], AggregateResponse[A.Rjn], AggregateEvent[A.Evt]] =
    new EventsourcedBehavior[AggregateEventsourcedBehavior[A]] {
      override type Command = AggregateCommand[A.Cmd]
      override type Response = AggregateResponse[A.Rjn]
      override type Event = AggregateEvent[A.Evt]

      override def handleCommand(a: AggregateEventsourcedBehavior[A])(command: AggregateCommand[A.Cmd]): NowOrDeferred[(AggregateResponse[A.Rjn], Seq[AggregateEvent[A.Evt]])] =
        if (a.processedCommands.contains(command.id)) {
          Now(AggregateResponse(command.id, Result.Accepted) -> Seq.empty)
        } else {
          A.handleCommand(a.aggregate)(command.command).map {
            case a@Accept(events) =>
              AggregateResponse(command.id, Result.Accepted) -> events.map(event => AggregateEvent(generate[EventId], event, Instant.now(), command.id))
            case r@Reject(rejection) =>
              AggregateResponse(command.id, Result.Rejected(rejection)) -> Seq.empty
          }
        }

      override def applyEvent(a: AggregateEventsourcedBehavior[A])(event: AggregateEvent[A.Evt]): AggregateEventsourcedBehavior[A] =
        a.copy(
          aggregate = A.applyEvent(a.aggregate)(event.event),
          processedCommands = a.processedCommands + event.causedBy
        )
    }

  def apply[Aggregate](aggregate: Aggregate): AggregateEventsourcedBehavior[Aggregate] = AggregateEventsourcedBehavior(aggregate, Set.empty)
}

