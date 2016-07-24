package aecor.core.aggregate

import java.time.Instant

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
case object Accepted extends Result[Nothing]
case class Rejected[+Rejection](rejection: Rejection) extends Result[Rejection]


case class DefaultAggregateActorBehavior[AggregateState, Command, Event, Rejection]
(aggregateState: AggregateState,
  processedCommands: Set[CommandId],
  commandHandler: CommandHandler[AggregateState, Command, Event, Rejection],
  projector: EventProjector[AggregateState, Event]
) {

  def snapshot: DefaultAggregateActorBehaviorState[AggregateState] = DefaultAggregateActorBehaviorState(aggregateState, processedCommands)

  def withSnapshot(snapshot: DefaultAggregateActorBehaviorState[AggregateState]) = copy(aggregateState = snapshot.entityState, processedCommands = snapshot.processedCommands)

  def applyEntityEvent(event: Event, causedBy: CommandId): DefaultAggregateActorBehavior[AggregateState, Command, Event, Rejection] =
    copy(aggregateState = projector(aggregateState, event), processedCommands = processedCommands + causedBy)

  def handleCommand(commandId: CommandId, command: Command): NowOrLater[AggregateDecision[Rejection, AggregateEventEnvelope[Event]]] =
    if (processedCommands.contains(commandId)) {
      AggregateDecision.accept()
    } else {
      commandHandler(aggregateState, command).map(_.map(event => AggregateEventEnvelope(generate[EventId], event, Instant.now(), commandId)))
    }
}

object DefaultAggregateActorBehavior {
  implicit def instance[AggregateState, Command, Event, Rejection]:
  AggregateActorBehavior[DefaultAggregateActorBehavior[AggregateState, Command, Event, Rejection], DefaultAggregateActorBehaviorState[AggregateState], HandleCommand[Command], AggregateResponse[Rejection], AggregateEventEnvelope[Event]] =
    new AggregateActorBehavior[DefaultAggregateActorBehavior[AggregateState, Command, Event, Rejection], DefaultAggregateActorBehaviorState[AggregateState], HandleCommand[Command], AggregateResponse[Rejection], AggregateEventEnvelope[Event]] {
      override def snapshot(a: DefaultAggregateActorBehavior[AggregateState, Command, Event, Rejection]): DefaultAggregateActorBehaviorState[AggregateState] =
        a.snapshot

      override def applySnapshot(a: DefaultAggregateActorBehavior[AggregateState, Command, Event, Rejection])(state: DefaultAggregateActorBehaviorState[AggregateState]): DefaultAggregateActorBehavior[AggregateState, Command, Event, Rejection] =
        a.withSnapshot(state)

      override def handleCommand(a: DefaultAggregateActorBehavior[AggregateState, Command, Event, Rejection])(command: HandleCommand[Command]): NowOrLater[CommandHandlerResult[AggregateResponse[Rejection], AggregateEventEnvelope[Event]]] =
        a.handleCommand(command.id, command.command).map {
          case Accept(events) => CommandHandlerResult(AggregateResponse(command.id, Accepted), events)
          case Reject(rejection) => CommandHandlerResult(AggregateResponse(command.id, Rejected(rejection)), Seq.empty)
        }
      override def applyEvent(a: DefaultAggregateActorBehavior[AggregateState, Command, Event, Rejection])(event: AggregateEventEnvelope[Event]): DefaultAggregateActorBehavior[AggregateState, Command, Event, Rejection] =
        a.applyEntityEvent(event.event, event.causedBy)
    }

  def apply[Aggregate, State, Command, Event, Rejection](aggregate: Aggregate)(implicit behavior: AggregateBehavior[Aggregate, State, Command, Event, Rejection], State: ClassTag[State],
    Command: ClassTag[Command],
    Event: ClassTag[Event]): DefaultAggregateActorBehavior[State, Command, Event, Rejection] =
    DefaultAggregateActorBehavior(behavior.initialState(aggregate), Set.empty,
                                                  behavior.commandHandler(aggregate),
                                                  behavior.eventProjector(aggregate))
}

case class DefaultAggregateActorBehaviorState[State](entityState: State, processedCommands: Set[CommandId])
