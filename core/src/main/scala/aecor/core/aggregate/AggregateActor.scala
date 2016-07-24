package aecor.core.aggregate

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}
import scala.collection.immutable.Seq
import aecor.core.aggregate.NowOrLater.{Deferred, Now}
import aecor.core.message._
import aecor.util.generate
import akka.NotUsed
import akka.actor.{ActorLogging, Props, Stash, Status}
import akka.cluster.sharding.ShardRegion
import akka.pattern._
import akka.persistence.journal.Tagged
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

case class CommandId(value: String) extends AnyVal
case class HandleCommand[+A](id: CommandId, command: A)

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

  def snapshot: AggregateActorState[AggregateState] = AggregateActorState(aggregateState, processedCommands)

  def withSnapshot(snapshot: AggregateActorState[AggregateState]) = copy(aggregateState = snapshot.entityState, processedCommands = snapshot.processedCommands)

  def applyEntityEvent(event: Event, causedBy: CommandId): DefaultAggregateActorBehavior[AggregateState, Command, Event, Rejection] =
    copy(aggregateState = projector(aggregateState, event), processedCommands = processedCommands + causedBy)

  def handleCommand(commandId: CommandId, command: Command): NowOrLater[AggregateDecision[Rejection, AggregateEventEnvelope[Event]]] =
    if (processedCommands.contains(commandId)) {
      NowOrLater.accept()
    } else {
      commandHandler(aggregateState, command).map(_.map(event => AggregateEventEnvelope(generate[EventId], event, Instant.now(), commandId)))
    }
}

object DefaultAggregateActorBehavior {
  implicit def instance[AggregateState, Command, Event, Rejection]:
  AggregateActorBehavior[DefaultAggregateActorBehavior[AggregateState, Command, Event, Rejection], AggregateActorState[AggregateState], HandleCommand[Command], AggregateResponse[Rejection], AggregateEventEnvelope[Event]] =
    new AggregateActorBehavior[DefaultAggregateActorBehavior[AggregateState, Command, Event, Rejection], AggregateActorState[AggregateState], HandleCommand[Command], AggregateResponse[Rejection], AggregateEventEnvelope[Event]] {
      override def snapshot(a: DefaultAggregateActorBehavior[AggregateState, Command, Event, Rejection]): AggregateActorState[AggregateState] =
        a.snapshot

      override def applySnapshot(a: DefaultAggregateActorBehavior[AggregateState, Command, Event, Rejection])(state: AggregateActorState[AggregateState]): DefaultAggregateActorBehavior[AggregateState, Command, Event, Rejection] =
        a.withSnapshot(state)

      override def handleCommand(a: DefaultAggregateActorBehavior[AggregateState, Command, Event, Rejection])(command: HandleCommand[Command]): NowOrLater[CommandHandlerResult[AggregateResponse[Rejection], AggregateEventEnvelope[Event]]] =
        a.handleCommand(command.id, command.command).map {
          case Accept(events) => CommandHandlerResult(AggregateResponse(command.id, Accepted), events)
          case Reject(rejection) => CommandHandlerResult(AggregateResponse(command.id, Rejected(rejection)), Seq.empty)
        }
      override def applyEvent(a: DefaultAggregateActorBehavior[AggregateState, Command, Event, Rejection])(event: AggregateEventEnvelope[Event]): DefaultAggregateActorBehavior[AggregateState, Command, Event, Rejection] =
        a.applyEntityEvent(event.event, event.causedBy)
    }
}

case class AggregateActorState[State](entityState: State, processedCommands: Set[CommandId])

case class CommandHandlerResult[Response, Event](response: Response, events: Seq[Event])

trait AggregateActorBehavior[A, State, Command, Response, Event] {
  def snapshot(a: A): State
  def applySnapshot(a: A)(state: State): A
  def handleCommand(a: A)(command: Command): NowOrLater[CommandHandlerResult[Response, Event]]
  def applyEvent(a: A)(event: Event): A
}

private [aecor] object AggregateActor {
  def props[Behavior, State, Command, Event, Response]
  (entityName: String,
    initialBehavior: Behavior,
    idleTimeout: FiniteDuration
  )(implicit Behavior: AggregateActorBehavior[Behavior, State, Command, Response, Event], Command: ClassTag[Command], State: ClassTag[State], Event: ClassTag[Event]): Props =
    Props(new AggregateActor(entityName, initialBehavior, idleTimeout))

  def extractEntityId[A: ClassTag](implicit correlation: Correlation[A]): ShardRegion.ExtractEntityId = {
    case m @ HandleCommand(_, a: A) ⇒ (correlation(a), m)
  }
  def extractShardId[A: ClassTag](numberOfShards: Int)(implicit correlation: Correlation[A]): ShardRegion.ExtractShardId = {
    case m @ HandleCommand(_, a: A) => ExtractShardId(correlation(a), numberOfShards)
  }
}

private [aecor] class AggregateActor[Behavior, State, Command, Event, Response]
(entityName: String,
 initialBehavior: Behavior,
 val idleTimeout: FiniteDuration
)(implicit Behavior: AggregateActorBehavior[Behavior, State, Command, Response, Event],
  Command: ClassTag[Command], State: ClassTag[State], Event: ClassTag[Event]) extends PersistentActor
  with Stash
  with ActorLogging
  with Passivation {

  import context.dispatcher

  private case class HandleCommandHandlerResult(result: CommandHandlerResult[Response, Event])

  private val entityId: String = URLDecoder.decode(self.path.name, StandardCharsets.UTF_8.name())
  override val persistenceId: String = s"$entityName-$entityId"

  var behavior = initialBehavior

  log.debug("[{}] Starting...", persistenceId)

  private val tags = Set(entityName)

  private val recoveryStartTimestamp: Instant = Instant.now()

  override def receiveRecover: Receive = {
    case e: Event =>
      applyEvent(e)

    case SnapshotOffer(metadata, snapshot: State) =>
      log.debug("Applying snapshot [{}]", snapshot)
      behavior = Behavior.applySnapshot(behavior)(snapshot)

    case RecoveryCompleted =>
      log.debug("[{}] Recovery completed in [{} ms]", persistenceId, Duration.between(recoveryStartTimestamp, Instant.now()).toMillis)
      setIdleTimeout()
  }

  override def receiveCommand: Receive = receivePassivationMessages.orElse(receiveCommandMessage)

  def receiveCommandMessage: Receive = {
    case command: Command =>
      handleCommand(command)
    case other =>
      log.warning("[{}] Unknown message [{}]", persistenceId, other)
  }

  def handleCommand(command: Command) = {
    log.debug("Received command [{}]", command)
    Behavior.handleCommand(behavior)(command) match {
      case Now(result) =>
        runResult(result)

      case Deferred(deferred) =>
        deferred(context.dispatcher).map(HandleCommandHandlerResult).pipeTo(self)(sender)
        context.become {
          case HandleCommandHandlerResult(result) =>
            runResult(result)
            unstashAll()
            context.become(receiveCommand)
          case failure @ Status.Failure(e) =>
            log.error(e, "Deferred reaction failed")
            sender() ! failure
            unstashAll()
            context.become(receiveCommand)
          case _ =>
            stash()
        }
    }
  }


  def runResult(result: CommandHandlerResult[Response, Event]): Unit = {
    log.debug("Command handler result [{}]", result)
    val envelopes = result.events.map(Tagged(_, tags))
    persistAll(envelopes) {
      case Tagged(e: Event, _) =>
        applyEvent(e)
    }
    deferAsync(NotUsed) { _ =>
      sender() ! result.response
    }
  }

  def applyEvent(event: Event): Unit = {
    log.debug("Applying event [{}]", event)
    behavior = Behavior.applyEvent(behavior)(event)
    log.debug("New behavior [{}]", behavior)
  }
}