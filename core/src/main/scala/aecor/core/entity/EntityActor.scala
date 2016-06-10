package aecor.core.entity

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant

import aecor.core.EventBus
import aecor.core.entity.CommandHandlerResult.{Accept, Defer, Reject}
import aecor.core.message.{Message, MessageId}
import aecor.core.serialization.Encoder
import akka.actor.{ActorRef, Props, Stash}
import akka.pattern._
import akka.persistence.{AtLeastOnceDelivery, PersistentActor, RecoveryCompleted, SnapshotOffer}
import com.google.protobuf.ByteString
import io.aecor.message.protobuf.Messages.{DomainEvent, Timestamp}

import scala.collection.immutable.Queue
import scala.reflect.ClassTag
import EntityActor._
import aecor.core.logging.PersistentActorLogging

private [aecor] object EntityActor {

  case class Response[+Rejection, +Ack](ack: Ack, result: Result[Rejection])
  sealed trait Result[+Rejection]
  case object Accepted extends Result[Nothing]
  case class Rejected[+Rejection](rejection: Rejection) extends Result[Rejection]
  case object Stop
  def props[State: ClassTag, Command: ClassTag, Event: ClassTag: Encoder, Rejection]
  (entityName: String,
   initialState: State,
   commandHandler: CommandHandler.Aux[State, Command, Event, Rejection],
   eventProjector: EventProjector[State, Event],
   messageQueue: ActorRef
  ): Props = Props(new EntityActor(entityName, initialState, commandHandler, eventProjector, messageQueue))
}

private [aecor] object PublishState {
  def empty[Event]: PublishState[Event] = PublishState(0, Map.empty, Queue.empty)
}

private [aecor] case class PublishState[Event](publishedEventCounter: Long, eventNrToDeliveryId: Map[Long, Long], queue: Queue[EntityEventEnvelope[Event]]) {
  def outstandingEvent: Option[EntityEventEnvelope[Event]] = queue.headOption
  def enqueue(event: EntityEventEnvelope[Event]): PublishState[Event] =
    copy(queue = queue.enqueue(event))

  def withDeliveryId(eventNr: Long, deliveryId: Long): PublishState[Event] = copy(eventNrToDeliveryId = eventNrToDeliveryId.updated(eventNr, deliveryId))
  def deliveryId(eventNr: Long): Option[Long] = eventNrToDeliveryId.get(eventNr)

  def markOutstandingEventAsPublished: PublishState[Event] =
    queue.dequeueOption.map {
      case (_, newQueue) =>
        copy(publishedEventCounter = publishedEventCounter + 1, queue = newQueue, eventNrToDeliveryId = eventNrToDeliveryId - publishedEventCounter)
    }.getOrElse(this)
}

private [aecor] sealed trait EntityActorEvent
private [aecor] case class EntityEventEnvelope[Event](id: MessageId, entityName: String, event: Event, timestamp: Instant, causedBy: MessageId) extends EntityActorEvent
private [aecor] case class EventPublished(eventNr: Long) extends EntityActorEvent

private [aecor] case class MarkEventAsPublished(entityId: String, eventNr: Long)

private [aecor] case class EntityActorInternalState[State, Event](entityState: State, processedCommands: Set[MessageId]) {
  def applyEntityEvent(projector: EventProjector[State, Event])(event: Event, causedBy: MessageId): EntityActorInternalState[State, Event] =
    copy(entityState = projector(entityState, event), processedCommands = processedCommands + causedBy)
}

private [aecor] class EntityActor[State: ClassTag, Command: ClassTag, Event: ClassTag: Encoder, Rejection]
(entityName: String,
 initialState: State,
 commandHandler: CommandHandler.Aux[State, Command, Event, Rejection],
 eventProjector: EventProjector[State, Event],
 messageQueue: ActorRef
) extends PersistentActor
  with Stash
  with AtLeastOnceDelivery
  with PersistentActorLogging {
  import context.dispatcher

  case class HandleCommandHandlerResult(result: CommandHandlerResult[Event, Rejection])

  private val entityId: String = URLDecoder.decode(self.path.name, StandardCharsets.UTF_8.name())
  override val persistenceId: String = s"$entityName-$entityId"

  var state: EntityActorInternalState[State, Event] = EntityActorInternalState(initialState, Set.empty)
  var publishState = PublishState.empty[Event]
  def publishedEventCounter = publishState.publishedEventCounter


  override def preStart(): Unit = {
    log.debug("[{}] Starting...", persistenceId)
    super.preStart()
  }

  override def receiveRecover: Receive = {
    case eee @ EntityEventEnvelope(_, _, event: Event, _, causedBy) =>
      applyEventEnvelope(eee.asInstanceOf[EntityEventEnvelope[Event]])

    case ep: EventPublished =>
      applyEventPublished(ep)

    case SnapshotOffer(metadata, stateSnapshot: EntityActorInternalState[State, Event]) =>
      state = stateSnapshot

    case RecoveryCompleted =>
      log.debug("[{}] Recovery completed", persistenceId)
  }

  def handlingCommand: Receive = {
    case Message(id, command: Command, ack) =>
      if (state.processedCommands contains id) {
        sender() ! Response(ack, Accepted)
      } else {
        val reaction = commandHandler(state.entityState, command)
        runReaction(id, ack)(reaction)
      }
    case MarkEventAsPublished(_, eventNr) =>
      publishState.deliveryId(eventNr).foreach { _ =>
        persistAsync(EventPublished(eventNr))(applyEventPublished)
      }
    case other =>
      log.warning("[{}] Unknown message [{}]", persistenceId, other)
  }

  def runReaction(causedBy: MessageId, ack: Any)(commandHandlerResult: CommandHandlerResult[Event, Rejection]): Unit = commandHandlerResult match {
    case Accept(event: Event) =>
      persist(EntityEventEnvelope(MessageId(entityName, entityId, lastSequenceNr), entityName, event, Instant.now, causedBy)) { eventEnvelope =>
        applyEventEnvelope(eventEnvelope)
        sender() ! Response(ack, Accepted)
      }
    case Reject(rejection) =>
      sender() ! Response(ack, Rejected(rejection))
    case Defer(deferred) =>
      deferred.map(HandleCommandHandlerResult(_)).asFuture.pipeTo(self)(sender)
      context.become {
        case HandleCommandHandlerResult(value) =>
          runReaction(causedBy, ack)(value)
          unstashAll()
        case _ =>
          stash()
      }
  }

  def applyEventEnvelope(entityEventEnvelope: EntityEventEnvelope[Event]): Unit = {
    state = state.applyEntityEvent(eventProjector)(entityEventEnvelope.event, entityEventEnvelope.causedBy)
    if (numberOfUnconfirmed == 0) {
      publishEvent(entityEventEnvelope)
    } else {
      publishState = publishState.enqueue(entityEventEnvelope)
    }
  }

  def applyEventPublished(eventPublished: EventPublished): Unit = {
    val eventNr = eventPublished.eventNr
    publishState.deliveryId(eventNr).foreach { deliveryId =>
      publishState = publishState.markOutstandingEventAsPublished
      confirmDelivery(deliveryId)
      publishState.outstandingEvent.foreach { eee =>
        publishEvent(eee)
      }
    }
  }

  def publishEvent(entityEventEnvelope: EntityEventEnvelope[Event]): Unit = {
    deliver(messageQueue.path) { deliveryId =>
      publishState = publishState.withDeliveryId(publishedEventCounter, deliveryId)
      val domainEvent = DomainEvent(
        entityEventEnvelope.id.value,
        ByteString.copyFrom(implicitly[Encoder[Event]].encode(entityEventEnvelope.event)),
        Timestamp(entityEventEnvelope.timestamp.toEpochMilli),
        entityEventEnvelope.causedBy.value
      )
      EventBus.PublishMessage(entityName, entityId, domainEvent, Message("not-used", MarkEventAsPublished(entityId, publishedEventCounter)) , context.parent)
    }
  }

  override def receiveCommand: Receive = handlingCommand
}
