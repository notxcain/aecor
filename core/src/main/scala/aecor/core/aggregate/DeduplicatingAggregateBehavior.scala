package aecor.core.aggregate

import java.time.Instant

import aecor.core.aggregate.NowOrLater.Now
import aecor.core.message.Correlation
import aecor.util._

import scala.collection.immutable.Seq
import scala.reflect.ClassTag

case class CommandId(value: String) extends AnyVal
case class HandleCommand[+A](id: CommandId, command: A)
object HandleCommand {
  implicit def correlation[A](implicit A: Correlation[A]): Correlation[HandleCommand[A]] = Correlation.instance(c => A(c.command))
}

case class EventId(value: String) extends AnyVal

case class AggregateEventEnvelope[+Event](id: EventId, event: Event, timestamp: Instant, causedBy: CommandId) {
  def cast[EE: ClassTag]: Option[AggregateEventEnvelope[EE]] = event match {
    case e: EE => Some(this.asInstanceOf[AggregateEventEnvelope[EE]])
    case other => None
  }
}

case class AggregateResponse[+Rejection](causedBy: CommandId, result: Result[Rejection])
sealed trait Result[+Rejection]
object Result {
  case object Accepted extends Result[Nothing]
  case class Rejected[+Rejection](rejection: Rejection) extends Result[Rejection]
}

case class DeduplicatingAggregateBehavior[Aggregate](aggregate: Aggregate, processedCommands: Set[CommandId])

object DeduplicatingAggregateBehavior {
  implicit def instance[Aggregate, AggregateState, Command, Event, Rejection]
  (implicit aab: AggregateBehavior[Aggregate, AggregateState, Command, Result[Rejection], Event]):
  AggregateBehavior[DeduplicatingAggregateBehavior[Aggregate], DefaultAggregateActorBehaviorState[AggregateState], HandleCommand[Command], AggregateResponse[Rejection], AggregateEventEnvelope[Event]] =
    new AggregateBehavior[DeduplicatingAggregateBehavior[Aggregate], DefaultAggregateActorBehaviorState[AggregateState], HandleCommand[Command], AggregateResponse[Rejection], AggregateEventEnvelope[Event]] {
      override def getState(a: DeduplicatingAggregateBehavior[Aggregate]): DefaultAggregateActorBehaviorState[AggregateState] =
        DefaultAggregateActorBehaviorState(aab.getState(a.aggregate), a.processedCommands)

      override def setState(a: DeduplicatingAggregateBehavior[Aggregate])(state: DefaultAggregateActorBehaviorState[AggregateState]): DeduplicatingAggregateBehavior[Aggregate] =
        a.copy(aggregate = aab.setState(a.aggregate)(state.entityState), processedCommands = state.processedCommands)


      override def handleCommand(a: DeduplicatingAggregateBehavior[Aggregate])(command: HandleCommand[Command]): NowOrLater[CommandHandlerResult[AggregateResponse[Rejection], AggregateEventEnvelope[Event]]] =
        if (a.processedCommands.contains(command.id)) {
          Now(CommandHandlerResult(AggregateResponse(command.id, Result.Accepted), Seq.empty))
        } else {
          aab.handleCommand(a.aggregate)(command.command).map { case CommandHandlerResult(result, events) =>
            CommandHandlerResult(AggregateResponse(command.id, result), events.map(event => AggregateEventEnvelope(generate[EventId], event, Instant.now(), command.id)))
          }
        }
      override def applyEvent(a: DeduplicatingAggregateBehavior[Aggregate])(event: AggregateEventEnvelope[Event]): DeduplicatingAggregateBehavior[Aggregate] =
        a.copy(aggregate = aab.applyEvent(a.aggregate)(event.event), processedCommands = a.processedCommands + event.causedBy)
    }

  def apply[Aggregate](aggregate: Aggregate): DeduplicatingAggregateBehavior[Aggregate] = DeduplicatingAggregateBehavior(aggregate)
}

case class DefaultAggregateActorBehaviorState[State](entityState: State, processedCommands: Set[CommandId])
