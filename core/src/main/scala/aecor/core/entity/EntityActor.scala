package aecor.core.entity

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant

import aecor.core.bus.PublishEntityEvent
import aecor.core.entity.CommandHandlerResult.{Accept, Defer, Reject}
import aecor.core.entity.EventBusPublisherActor.PublishEvent
import aecor.core.logging.PersistentActorLogging
import aecor.core.message.{Message, MessageId}
import akka.actor.{Actor, ActorLogging, ActorRef, Props, Stash, Status}
import akka.pattern._
import akka.persistence.journal.Tagged
import akka.persistence.{AtLeastOnceDelivery, PersistentActor, RecoveryCompleted, SnapshotOffer}
import cats.std.future._
import aecor.core.concurrent._
import scala.collection.immutable.Queue
import scala.reflect.ClassTag


case class EntityResponse[+Rejection, +Ack](ack: Ack, result: Result[Rejection])
sealed trait Result[+Rejection]
case object Accepted extends Result[Nothing]
case class Rejected[+Rejection](rejection: Rejection) extends Result[Rejection]

private [aecor] object EntityActor {



  case object Stop
  def props[State: ClassTag, Command: ClassTag, Event: ClassTag, Rejection, EventBus]
  (entityName: String,
   initialState: State,
   commandHandler: CommandHandler[State, Command, Event, Rejection],
   eventProjector: EventProjector[State, Event],
   eventBus: EventBus,
   preserveEventOrderInEventBus: Boolean
  )(implicit eventBusPublisher: PublishEntityEvent[EventBus, Event]): Props = Props(new EntityActor(entityName, initialState, commandHandler, eventProjector, eventBus, preserveEventOrderInEventBus))
}

private [aecor] object PublishState {
  def empty[Event]: PublishState[Event] = PublishState(Map.empty, Queue.empty)
}

private [aecor] case class PublishState[+Event](eventNrToDeliveryId: Map[Long, Long], queue: Queue[Event]) {
  def outstandingEvent: Option[Event] = queue.headOption
  def enqueue[E >: Event](event: E): PublishState[E] =
    copy(queue = queue.enqueue(event))
  def withDeliveryId(eventNr: Long, deliveryId: Long): PublishState[Event] = copy(eventNrToDeliveryId = eventNrToDeliveryId.updated(eventNr, deliveryId))
  def deliveryId(eventNr: Long): Option[Long] = eventNrToDeliveryId.get(eventNr)

  def markOutstandingEventAsPublished(eventNr: Long): PublishState[Event] =
    copy(
      eventNrToDeliveryId = eventNrToDeliveryId - eventNr,
      queue = queue.dequeueOption.map(_._2).getOrElse(queue)
    )
}



private [aecor] case class MarkEventAsPublished(entityId: String, eventNr: Long)

private [aecor] case class EntityActorState[State](entityState: State, processedCommands: Set[MessageId]) {
  def cast[S: ClassTag]: Option[EntityActorState[S]] = this match {
    case EntityActorState(es: S, _) => Some(this.asInstanceOf[EntityActorState[S]])
    case _ => None
  }

  def hasProcessedCommandWithId(messageId: MessageId): Boolean = processedCommands(messageId)

  def applyEntityEvent[Event](projector: EventProjector[State, Event])(event: Event, causedBy: MessageId): EntityActorState[State] =
    copy(entityState = projector(entityState, event), processedCommands = processedCommands + causedBy)
  def handleEntityCommand[Command, Event, Rejection](commandHandler: CommandHandler[State, Command, Event, Rejection])(command: Command): CommandHandlerResult[Event, Rejection] =
    commandHandler(entityState, command)
}

private [aecor] class EntityActor[State: ClassTag, Command: ClassTag, Event: ClassTag, Rejection, EventBus]
(entityName: String,
 initialState: State,
 commandHandler: CommandHandler[State, Command, Event, Rejection],
 eventProjector: EventProjector[State, Event],
 eventBus: EventBus,
 preserveEventOrderInEventBus: Boolean
)(implicit eventBusPublisher: PublishEntityEvent[EventBus, Event]) extends PersistentActor
  with Stash
  with AtLeastOnceDelivery
  with PersistentActorLogging {
  import context.dispatcher

  case class HandleCommandHandlerResult(result: CommandHandlerResult[Event, Rejection])

  private val entityId: String = URLDecoder.decode(self.path.name, StandardCharsets.UTF_8.name())
  override val persistenceId: String = s"$entityName-$entityId"

  var state: EntityActorState[State] = EntityActorState(initialState, Set.empty)
  var publishState = PublishState.empty[EntityEventEnvelope[Event]]

  val eventBusActor = context.actorOf(EventBusPublisherActor.props[EventBus, Event](entityName, entityId, eventBus), "eventBusPublisher")

  override def preStart(): Unit = {
    log.debug("[{}] Starting...", persistenceId)
    super.preStart()
  }

  override def receiveRecover: Receive = {
    case e: EntityEventEnvelope[_] =>
      e.cast[Event] match {
        case Some(envelope) => applyEventEnvelope(envelope)
        case None => throw new IllegalArgumentException(s"Unexpected event ${e.event}")
      }

    case ep: EventPublished =>
      applyEventPublished(ep)

    case SnapshotOffer(metadata, stateSnapshot: EntityActorState[_]) =>
      stateSnapshot.cast[State] match {
        case Some(snapshot) => state = snapshot
        case None => throw new IllegalArgumentException(s"Unexpected snapshot $stateSnapshot")
      }

    case RecoveryCompleted =>
      log.debug("[{}] Recovery completed", persistenceId)
  }

  def handlingCommand: Receive = {
    case Message(id, command: Command, ack) =>
      if (state.hasProcessedCommandWithId(id)) {
        sender() ! EntityResponse(ack, Accepted)
      } else {
        val reaction = state.handleEntityCommand(commandHandler)(command)
        runResult(id, ack)(reaction)
      }
    case MarkEventAsPublished(_, eventNr) =>
      publishState.deliveryId(eventNr).foreach { _ =>
        val published = EventPublished(eventNr)
        persistAsync(Tagged(published, Set(entityName)))(_ => applyEventPublished(published))
      }
    case Status.Failure(e) =>
      log.error(e, "Failed to publish event")
    case other =>
      log.warning("[{}] Unknown message [{}]", persistenceId, other)
  }

  def runResult(causedBy: MessageId, ack: Any)(result: CommandHandlerResult[Event, Rejection]): Unit = result match {
    case Accept(events) =>
      val envelopes = events.zipWithIndex
        .map {
          case (event, idx) => EntityEventEnvelope(MessageId(entityName, entityId, lastSequenceNr + idx), event, Instant.now, causedBy)
        }
        .map(envelope => Tagged(envelope, Set(entityName)))
          .toList
      persistAll(envelopes) {
        case Tagged(e: EntityEventEnvelope[Event], _) =>
          applyEventEnvelope(e)
      }
      deferAsync("") { _ =>
        sender() ! EntityResponse(ack, Accepted)
      }
      context.become(handlingCommand)
    case Reject(rejection) =>
      sender() ! EntityResponse(ack, Rejected(rejection))
      context.become(handlingCommand)
    case Defer(deferred) =>
      deferred.map(HandleCommandHandlerResult(_)).asFuture.pipeTo(self)(sender)
      context.become {
        case HandleCommandHandlerResult(deferredResult) =>
          runResult(causedBy, ack)(deferredResult)
          unstashAll()
        case failure @ Status.Failure(e) =>
          log.error(e, "Deferred reaction failed")
          sender() ! failure
          context.become(handlingCommand)
          unstashAll()
        case _ =>
          stash()
      }
  }

  def applyEventEnvelope(entityEventEnvelope: EntityEventEnvelope[Event]): Unit = {
    state = state.applyEntityEvent(eventProjector)(entityEventEnvelope.event, entityEventEnvelope.causedBy)
    if (canPublish) {
      publishEvent(entityEventEnvelope)
    } else {
      enqueueEvent(entityEventEnvelope)
    }
  }

  def canPublish = !preserveEventOrderInEventBus || (numberOfUnconfirmed == 0)

  def publishEvent(entityEventEnvelope: EntityEventEnvelope[Event]): Unit = {
    deliver(eventBusActor.path) { deliveryId =>
      publishState = publishState.withDeliveryId(lastSequenceNr, deliveryId)
      PublishEvent(entityEventEnvelope, Message("not-used", MarkEventAsPublished(entityId, lastSequenceNr)), context.parent)
    }
  }

  def enqueueEvent(entityEventEnvelope: EntityEventEnvelope[Event]): Unit = {
    publishState = publishState.enqueue(entityEventEnvelope)
  }

  def applyEventPublished(eventPublished: EventPublished): Unit = {
    val eventNr = eventPublished.eventNr
    publishState.deliveryId(eventNr).foreach { deliveryId =>
      confirmDelivery(deliveryId)
      publishState = publishState.markOutstandingEventAsPublished(eventNr)
      publishState.outstandingEvent.foreach { eee =>
        publishEvent(eee)
      }
    }
  }

  override def receiveCommand: Receive = handlingCommand
}


object EventBusPublisherActor {
  case class PublishEvent[Event, Reply](eventEnvelope: EntityEventEnvelope[Event], replyWith: Reply, replyTo: ActorRef)
  def props[EventBus, Event: ClassTag](entityName: String, entityId: String, eventBus: EventBus)(implicit ev: PublishEntityEvent[EventBus, Event]): Props =
    Props(new EventBusPublisherActor[EventBus, Event](entityName, entityId, eventBus))
}

class EventBusPublisherActor[EventBus, Event: ClassTag](entityName: String, entityId: String, eventBus: EventBus)(implicit eventBusPublisher: PublishEntityEvent[EventBus, Event]) extends Actor with ActorLogging {
  import context.dispatcher
  override def receive: Receive = {
    case PublishEvent(eventEnvelope, replyWith, replyTo) =>
      eventEnvelope.cast[Event] match {
        case Some(e) =>
          eventBusPublisher
            .publish(eventBus)(entityName, entityId, e)
            .map(_ => replyWith)
            .pipeTo(replyTo)
          ()
        case None =>
          log.warning("Unexpected event envelope [{}]", eventEnvelope)
          ()
      }
  }
}