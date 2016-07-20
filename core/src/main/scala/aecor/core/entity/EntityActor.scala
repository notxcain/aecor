package aecor.core.entity

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}

import aecor.core.concurrent._
import aecor.core.entity.CommandHandlerResult.{Accept, Defer, Reject}
import aecor.core.logging.PersistentActorLogging
import aecor.core.message.{Message, MessageId, Passivation}
import akka.NotUsed
import akka.actor.{Props, Stash, Status}
import akka.pattern._
import akka.persistence.journal.Tagged
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}
import cats.std.future._

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag


case class EntityResponse[+Rejection, +Ack](ack: Ack, result: Result[Rejection])
sealed trait Result[+Rejection]
case object Accepted extends Result[Nothing]
case class Rejected[+Rejection](rejection: Rejection) extends Result[Rejection]

private [aecor] object EntityActor {
  def props[State: ClassTag, Command: ClassTag, Event: ClassTag, Rejection, EventBus]
  (entityName: String,
   initialState: State,
   commandHandler: CommandHandler[State, Command, Event, Rejection],
   eventProjector: EventProjector[State, Event],
   idleTimeout: FiniteDuration
  ): Props = Props(new EntityActor(entityName, initialState, commandHandler, eventProjector, idleTimeout))
}


private [aecor] case class EntityActorState[State](entityState: State, processedCommands: Set[MessageId]) {
  def cast[S: ClassTag]: Option[EntityActorState[S]] = this match {
    case EntityActorState(es: S, _) => Some(this.asInstanceOf[EntityActorState[S]])
    case _ => None
  }

  def shouldHandleCommand(messageId: MessageId): Boolean = processedCommands(messageId)

  def applyEntityEvent[Event](projector: EventProjector[State, Event])(event: Event, causedBy: MessageId): EntityActorState[State] =
    copy(entityState = projector(entityState, event), processedCommands = processedCommands + causedBy)

  def handleEntityCommand[Command, Event, Rejection](commandHandler: CommandHandler[State, Command, Event, Rejection])(command: Command): CommandHandlerResult[Rejection, Event] =
    commandHandler(entityState, command)
}

private [aecor] class EntityActor[State: ClassTag, Command: ClassTag, Event: ClassTag, Rejection]
(entityName: String,
 initialState: State,
 commandHandler: CommandHandler[State, Command, Event, Rejection],
 eventProjector: EventProjector[State, Event],
 val idleTimeout: FiniteDuration
) extends PersistentActor
  with Stash
  with PersistentActorLogging
  with Passivation {
  import context.dispatcher

  case class HandleCommandHandlerResult(result: CommandHandlerResult[Rejection, Event])

  private val entityId: String = URLDecoder.decode(self.path.name, StandardCharsets.UTF_8.name())
  override val persistenceId: String = s"$entityName-$entityId"

  var state: EntityActorState[State] = EntityActorState(initialState, Set.empty)

  var recoveryStartTimestamp: Option[Instant] = None

  override def preStart(): Unit = {
    log.debug("[{}] Starting...", persistenceId)
    recoveryStartTimestamp = Some(Instant.now())
    super.preStart()
  }

  override def receiveRecover: Receive = {
    case e: PersistentEntityEventEnvelope[_] =>
      e.cast[Event] match {
        case Some(envelope) => applyEventEnvelope(envelope)
        case None => throw new IllegalArgumentException(s"Unexpected event ${e.event}")
      }

    case SnapshotOffer(metadata, stateSnapshot: EntityActorState[_]) =>
      stateSnapshot.cast[State] match {
        case Some(snapshot) =>
          log.debug("Applying snapshot [{}]", snapshot)
          state = snapshot
        case None =>
          log.warning(s"Unexpected snapshot $stateSnapshot")
      }

    case RecoveryCompleted =>
      recoveryStartTimestamp.foreach { start =>
        log.debug("[{}] Recovery completed in [{} ms]", persistenceId, Duration.between(start, Instant.now()).toMillis)
      }
      setIdleTimeout()
  }

  override def receiveCommand: Receive = receivePassivationMessages.orElse(receiveCommandMessage)

  def receiveCommandMessage: Receive = {
    case m @ Message(id, command: Command, ack) =>
      log.debug("Received command message [{}]", m)
      if (state.shouldHandleCommand(id)) {
        sender() ! EntityResponse(ack, Accepted)
        log.debug("Message already processed")
      } else {
        val result = state.handleEntityCommand(commandHandler)(command)
        log.debug("Command handler result [{}]", result)
        runResult(id, ack)(result)
      }
    case other =>
      log.warning("[{}] Unknown message [{}]", persistenceId, other)
  }

  def runResult(causedBy: MessageId, ack: Any)(result: CommandHandlerResult[Rejection, Event]): Unit = result match {
    case Accept(events) =>
      val envelopes = events.map { event =>
        Tagged(PersistentEntityEventEnvelope(MessageId.generate, event, Instant.now, causedBy), Set(entityName))
      }.toList
      persistAll(envelopes) {
        case Tagged(e: PersistentEntityEventEnvelope[Event], _) =>
          applyEventEnvelope(e)
      }
      deferAsync(NotUsed) { _ =>
        sender() ! EntityResponse(ack, Accepted)
      }
    case Reject(rejection) =>
      sender() ! EntityResponse(ack, Rejected(rejection))
    case Defer(deferred) =>
      deferred.map(HandleCommandHandlerResult).asFuture.pipeTo(self)(sender)
      context.become {
        case HandleCommandHandlerResult(deferredResult) =>
          log.debug("Command handler result [{}]", deferredResult)
          runResult(causedBy, ack)(deferredResult)
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

  def applyEventEnvelope(entityEventEnvelope: PersistentEntityEventEnvelope[Event]): Unit = {
    log.debug("Applying event [{}]", entityEventEnvelope)
    state = state.applyEntityEvent(eventProjector)(entityEventEnvelope.event, entityEventEnvelope.causedBy)
    log.debug("New state [{}]", state)
  }
}