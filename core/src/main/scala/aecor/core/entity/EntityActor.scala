package aecor.core.entity

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant

import aecor.core.bus.EventBusPublisher
import aecor.core.entity.CommandHandlerResult.{Accept, Defer, Reject}
import aecor.core.entity.EntityActor._
import aecor.core.entity.EventBusPublisherActor.PublishEvent
import aecor.core.logging.PersistentActorLogging
import aecor.core.message.{Message, MessageId}
import aecor.core.serialization.Encoder
import akka.actor.{Actor, ActorLogging, ActorRef, Props, Stash}
import akka.pattern._
import akka.persistence.journal.Tagged
import akka.persistence.{AtLeastOnceDelivery, PersistentActor, RecoveryCompleted, SnapshotOffer}

import scala.collection.immutable.Queue
import scala.reflect.ClassTag

private [aecor] object EntityActor {

  case class Response[+Rejection, +Ack](ack: Ack, result: Result[Rejection])
  sealed trait Result[+Rejection]
  case object Accepted extends Result[Nothing]
  case class Rejected[+Rejection](rejection: Rejection) extends Result[Rejection]
  case object Stop
  def props[State: ClassTag, Command: ClassTag, Event: ClassTag: Encoder, Rejection, EventBus]
  (entityName: String,
   initialState: State,
   commandHandler: CommandHandler.Aux[State, Command, Event, Rejection],
   eventProjector: EventProjector[State, Event],
   eventBus: EventBus
  )(implicit eventBusPublisher: EventBusPublisher[EventBus, Event]): Props = Props(new EntityActor(entityName, initialState, commandHandler, eventProjector, eventBus))
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
case class EntityEventEnvelope[Event](id: MessageId, event: Event, timestamp: Instant, causedBy: MessageId) extends EntityActorEvent
private [aecor] case class EventPublished(eventNr: Long) extends EntityActorEvent

private [aecor] case class MarkEventAsPublished(entityId: String, eventNr: Long)

private [aecor] case class EntityActorInternalState[State, Event](entityState: State, processedCommands: Set[MessageId]) {
  def applyEntityEvent(projector: EventProjector[State, Event])(event: Event, causedBy: MessageId): EntityActorInternalState[State, Event] =
    copy(entityState = projector(entityState, event), processedCommands = processedCommands + causedBy)
}

private [aecor] class EntityActor[State: ClassTag, Command: ClassTag, Event: ClassTag: Encoder, Rejection, EventBus]
(entityName: String,
 initialState: State,
 commandHandler: CommandHandler[State, Command, Event, Rejection],
 eventProjector: EventProjector[State, Event],
 eventBus: EventBus
)(implicit eventBusPublisher: EventBusPublisher[EventBus, Event]) extends PersistentActor
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
  val eventBusActor = context.actorOf(Props(new EventBusPublisherActor[EventBus, Event](entityName, entityId, eventBus)))

  override def preStart(): Unit = {
    log.debug("[{}] Starting...", persistenceId)
    super.preStart()
  }

  override def receiveRecover: Receive = {
    case eee @ EntityEventEnvelope(_, event: Event, _, causedBy) =>
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
        val published = EventPublished(eventNr)
        persistAsync(Tagged(published, Set(entityName)))(_ => applyEventPublished(published))
      }
    case other =>
      log.warning("[{}] Unknown message [{}]", persistenceId, other)
  }

  def runReaction(causedBy: MessageId, ack: Any)(commandHandlerResult: CommandHandlerResult[Event, Rejection]): Unit = commandHandlerResult match {
    case Accept(event) =>
      val envelope = EntityEventEnvelope(MessageId(entityName, entityId, lastSequenceNr), event, Instant.now, causedBy)
      persist(Tagged(envelope, Set(entityName))) { _ =>
        applyEventEnvelope(envelope)
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
    deliver(eventBusActor.path) { deliveryId =>
      publishState = publishState.withDeliveryId(publishedEventCounter, deliveryId)
      PublishEvent(
        entityEventEnvelope.id,
        entityEventEnvelope.event,
        entityEventEnvelope.timestamp,
        entityEventEnvelope.causedBy,
        Message("not-used", MarkEventAsPublished(entityId, publishedEventCounter)),
        context.parent
      )
    }
  }

  override def receiveCommand: Receive = handlingCommand
}


object EventBusPublisherActor {
  case class PublishEvent[Event, Reply](id: MessageId, event: Event, timestamp: Instant, causedBy: MessageId, replyWith: Reply, replyTo: ActorRef)
}


class EventBusPublisherActor[EventBus, Event: ClassTag](entityName: String, entityId: String, eventBus: EventBus)(implicit eventBusPublisher: EventBusPublisher[EventBus, Event]) extends Actor with ActorLogging {
  import context.dispatcher
  override def receive: Receive = {
    case PublishEvent(id, event: Event, timestamp: Instant, causedBy: MessageId, replyWith: Any, replyTo: ActorRef) =>
      eventBusPublisher
        .publish(eventBus)(entityName, entityId, EventBusPublisher.DomainEventEnvelope(id, event, timestamp, causedBy))
        .map(_ => replyWith)
        .pipeTo(replyTo)
      ()
  }
}