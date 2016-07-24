package aecor.core.aggregate

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}

import aecor.core.aggregate.CommandHandlerResult.{Accept, Defer, Reject}
import aecor.core.concurrent._
import aecor.core.message._
import akka.NotUsed
import akka.actor.{ActorLogging, Props, Stash, Status}
import akka.cluster.sharding.ShardRegion
import akka.pattern._
import akka.persistence.journal.Tagged
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}
import cats.std.future._
import aecor.util.generate

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

private [aecor] object AggregateActor {
  def props[State: ClassTag, Command: ClassTag, Event: ClassTag, Rejection]
  (entityName: String,
   initialState: State,
   commandHandler: CommandHandler[State, Command, Event, Rejection],
   eventProjector: EventProjector[State, Event],
   idleTimeout: FiniteDuration
  ): Props = Props(new AggregateActor(entityName, initialState, commandHandler, eventProjector, idleTimeout))

  def extractEntityId[A: ClassTag](implicit correlation: Correlation[A]): ShardRegion.ExtractEntityId = {
    case m @ HandleCommand(_, a: A) ⇒ (correlation(a), m)
  }
  def extractShardId[A: ClassTag](numberOfShards: Int)(implicit correlation: Correlation[A]): ShardRegion.ExtractShardId = {
    case m @ HandleCommand(_, a: A) => ExtractShardId(correlation(a), numberOfShards)
  }
}


private [aecor] case class AggregateActorState[State](entityState: State, processedCommands: Set[CommandId]) {
  def cast[S: ClassTag]: Option[AggregateActorState[S]] = this match {
    case AggregateActorState(es: S, _) => Some(this.asInstanceOf[AggregateActorState[S]])
    case _ => None
  }

  def shouldHandleCommand(commandId: CommandId): Boolean = processedCommands(commandId)

  def applyEntityEvent[Event](projector: EventProjector[State, Event])(event: Event, causedBy: CommandId): AggregateActorState[State] =
    copy(entityState = projector(entityState, event), processedCommands = processedCommands + causedBy)

  def handleEntityCommand[Command, Event, Rejection](commandHandler: CommandHandler[State, Command, Event, Rejection])(command: Command): CommandHandlerResult[Rejection, Event] =
    commandHandler(entityState, command)
}

private [aecor] class AggregateActor[State: ClassTag, Command: ClassTag, Event: ClassTag, Rejection]
(entityName: String,
 initialState: State,
 commandHandler: CommandHandler[State, Command, Event, Rejection],
 eventProjector: EventProjector[State, Event],
 val idleTimeout: FiniteDuration
) extends PersistentActor
  with Stash
  with ActorLogging
  with Passivation {

  import context.dispatcher

  private case class HandleCommandHandlerResult(result: CommandHandlerResult[Rejection, Event])

  private val entityId: String = URLDecoder.decode(self.path.name, StandardCharsets.UTF_8.name())
  override val persistenceId: String = s"$entityName-$entityId"

  var state: AggregateActorState[State] = AggregateActorState(initialState, Set.empty)

  log.debug("[{}] Starting...", persistenceId)

  val recoveryStartTimestamp: Instant = Instant.now()

  override def receiveRecover: Receive = {
    case e: AggregateEventEnvelope[_] =>
      e.cast[Event] match {
        case Some(envelope) => applyEventEnvelope(envelope)
        case None => throw new IllegalArgumentException(s"Unexpected event ${e.event}")
      }

    case SnapshotOffer(metadata, stateSnapshot: AggregateActorState[_]) =>
      stateSnapshot.cast[State] match {
        case Some(snapshot) =>
          log.debug("Applying snapshot [{}]", snapshot)
          state = snapshot
        case None =>
          log.warning(s"Unexpected snapshot $stateSnapshot")
      }

    case RecoveryCompleted =>
      log.debug("[{}] Recovery completed in [{} ms]", persistenceId, Duration.between(recoveryStartTimestamp, Instant.now()).toMillis)
      setIdleTimeout()
  }

  override def receiveCommand: Receive = receivePassivationMessages.orElse(receiveCommandMessage)

  def receiveCommandMessage: Receive = {
    case HandleCommand(id, command: Command) =>
      log.debug("Received command message id = [{}] command = [{}]", id, command)
      if (state.shouldHandleCommand(id)) {
        sender() ! AggregateResponse(id, Accepted)
        log.debug("Message already processed")
      } else {
        val result = state.handleEntityCommand(commandHandler)(command)
        log.debug("Command handler result [{}]", result)
        runResult(id)(result)
      }
    case other =>
      log.warning("[{}] Unknown message [{}]", persistenceId, other)
  }

  def runResult(causedBy: CommandId)(result: CommandHandlerResult[Rejection, Event]): Unit = result match {
    case Accept(events) =>
      val envelopes = events.map { event =>
        Tagged(AggregateEventEnvelope(generate[EventId], event, Instant.now(), causedBy), Set(entityName))
      }.toList
      persistAll(envelopes) {
        case Tagged(e: AggregateEventEnvelope[Event], _) =>
          applyEventEnvelope(e)
      }
      deferAsync(NotUsed) { _ =>
        sender() ! AggregateResponse(causedBy, Accepted)
      }

    case Reject(rejection) =>
      sender() ! AggregateResponse(causedBy, Rejected(rejection))

    case Defer(deferred) =>
      deferred.map(HandleCommandHandlerResult).asFuture.pipeTo(self)(sender)
      context.become {
        case HandleCommandHandlerResult(deferredResult) =>
          log.debug("Command handler result [{}]", deferredResult)
          runResult(causedBy)(deferredResult)
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

  def applyEventEnvelope(entityEventEnvelope: AggregateEventEnvelope[Event]): Unit = {
    log.debug("Applying event [{}]", entityEventEnvelope)
    state = state.applyEntityEvent(eventProjector)(entityEventEnvelope.event, entityEventEnvelope.causedBy)
    log.debug("New state [{}]", state)
  }
}