package aecor.core.aggregate

import java.time.Instant

import aecor.core.aggregate.NowOrLater.{Deferred, Now}
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

  case object Accepted extends Result[Nothing]

  case class Rejected[+Rejection](rejection: Rejection) extends Result[Rejection]

}

case class HandlerResult[Response, Event](response: Response, events: Seq[Event])

case class DefaultBehavior[Aggregate](aggregate: Aggregate, processedCommands: Set[CommandId])

case class AggregateResult[+R, +E](response: AggregateResponse[R], events: Seq[AggregateEvent[E]])

sealed trait AggregateDecision[+R, +E]
case class Accept[+E](events: Seq[E]) extends AggregateDecision[Nothing, E]
case class Reject[+R](rejection: R) extends AggregateDecision[R, Nothing]

trait AggregateBehavior[A] {
  type Cmd
  type Evt
  type Rjn
  def handleCommand(a: A)(command: Cmd): NowOrLater[AggregateDecision[Rjn, Evt]]
  def applyEvent(a: A)(e: Evt): A
}

object AggregateBehavior {
  object syntax {
    def accept[R, E](events: E*): AggregateDecision[R, E] = Accept(events.toVector)
    def reject[R, E](rejection: R): AggregateDecision[R, E] = Reject(rejection)
    def defer[R, E](f: ExecutionContext => Future[AggregateDecision[R, E]]): NowOrLater[AggregateDecision[R, E]] = Deferred(f)

    implicit def fromDecision[R, E](decision: AggregateDecision[R, E]): NowOrLater[AggregateDecision[R, E]] =
      Now(decision)

    def handle[State, In, Out](state: State, command: In)(f: State => In => Out): Out = f(state)(command)
  }
  type Aux[A, Command0, Rejection0, Event0] = AggregateBehavior[A] {
    type Cmd = Command0
    type Evt = Event0
    type Rjn = Rejection0
  }
}

object DefaultBehavior {
  implicit def eventsourced[A](implicit A: AggregateBehavior[A]): EventsourcedActorBehavior.Aux[DefaultBehavior[A], AggregateCommand[A.Cmd], AggregateResponse[A.Rjn], AggregateEvent[A.Evt]] =
    new EventsourcedActorBehavior[DefaultBehavior[A]] {
      override type Command = AggregateCommand[A.Cmd]
      override type Response = AggregateResponse[A.Rjn]
      override type Event = AggregateEvent[A.Evt]

      override def handleCommand(a: DefaultBehavior[A])(command: AggregateCommand[A.Cmd]): NowOrLater[(AggregateResponse[A.Rjn], Seq[AggregateEvent[A.Evt]])] =
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

      override def applyEvent(a: DefaultBehavior[A])(event: AggregateEvent[A.Evt]): DefaultBehavior[A] =
        a.copy(
          aggregate = A.applyEvent(a.aggregate)(event.event),
          processedCommands = a.processedCommands + event.causedBy
        )
    }

  def apply[Aggregate](aggregate: Aggregate): DefaultBehavior[Aggregate] = DefaultBehavior(aggregate, Set.empty)
}

