package aecor.core.scheduler

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.{Instant, LocalDateTime, ZoneId}

import aecor.core.bus.PublishEntityEvent
import aecor.core.entity.{EntityEventEnvelope, EventBusPublisherActor}
import aecor.core.logging.PersistentActorLogging
import aecor.core.message.{ExtractShardId, Message, MessageId}
import aecor.core.scheduler.ScheduleActor._
import akka.Done
import akka.actor.{Actor, ActorRef, Cancellable, Props}
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId}
import akka.persistence.{AtLeastOnceDelivery, PersistentActor}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}

import scala.concurrent.duration._

object ScheduleActorSupervisor {
  def calculateTimeBucket(date: LocalDateTime, bucketLength: Duration = 60.seconds): Long = {
    date.atZone(ZoneId.systemDefault()).toEpochSecond / bucketLength.toSeconds
  }
  def extractEntityId: ExtractEntityId = {
    case c @ AddScheduleEntry(scheduleName, dueDate, entryId) =>
      (scheduleName + "-" + calculateTimeBucket(dueDate), c)
  }
  def extractShardId: ExtractShardId = {
    case c @ AddScheduleEntry(scheduleName, dueDate, entryId) =>
      ExtractShardId(scheduleName + "-" + calculateTimeBucket(dueDate), 100)
  }
  def props[EventBus](eventBus: EventBus)(implicit ev: PublishEntityEvent[EventBus, ScheduleActor.PublicEvent]): Props =
    Props(new ScheduleActorSupervisor[EventBus](eventBus))
}

class ScheduleActorSupervisor[EventBus](eventBus: EventBus)(implicit ev: PublishEntityEvent[EventBus, ScheduleActor.PublicEvent]) extends Actor {

  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(context.system), "ScheduleActorSupervisor")

  val (scheduleName, timeBucket) = {
    val name = URLDecoder.decode(self.path.name, StandardCharsets.UTF_8.name())
    val components = name.split("-")
    (components.dropRight(1).mkString("-"), components.last)
  }

  var tickControl: Option[Cancellable] = None
  var worker = context.actorOf(ScheduleActor.props(scheduleName, timeBucket, eventBus, context.parent), "worker")

  override def postStop(): Unit = {
    tickControl.foreach(_.cancel())
  }

  override def preStart(): Unit = {
    val graph = Source.tick(5.seconds, 1.second, FireDueEntries).toMat(Sink.actorRef(worker, Done))(Keep.left)
    tickControl = Some(graph.run())
  }

  override def receive: Receive = {
    case c: AddScheduleEntry => worker.forward(c)
  }
}

object ScheduleActor {
  sealed trait Command
  case class AddScheduleEntry(scheduleName: String, dueDate: LocalDateTime, entryId: String) extends Command
  private [scheduler] case object FireDueEntries extends Command
  private [scheduler] case class MarkEventAsPublished(deliveryId: Long) extends Command

  sealed trait Event
  sealed trait PublicEvent extends Event
  case class ScheduleEntryAdded(scheduleName: String, dueDate: LocalDateTime, entryId: String) extends PublicEvent
  case class ScheduleEntryFired(scheduleName: String, entryId: String) extends PublicEvent
  private [scheduler] case class EventPublished(scheduleName: String, seqNr: Long) extends Event

  def props[EventBus](scheduleName: String, timeBucket: String, eventBus: EventBus, regionRef: ActorRef)(implicit ev: PublishEntityEvent[EventBus, PublicEvent]): Props =
    Props(new ScheduleActor(scheduleName, timeBucket, eventBus, regionRef: ActorRef))
}

class ScheduleActor[EventBus](scheduleName: String, timeBucket: String, eventBus: EventBus, regionRef: ActorRef)(implicit ev: PublishEntityEvent[EventBus, PublicEvent])
  extends PersistentActor
  with AtLeastOnceDelivery
  with PersistentActorLogging {

  override val persistenceId: String = "Schedule-" + scheduleName + "-" + timeBucket

  case class Entry(id: String, dueDate: LocalDateTime)
  case class State(entries: List[Entry]) {
    def addEntry(entryId: String, dueDate: LocalDateTime): State =
      copy(entries = Entry(entryId, dueDate) :: entries)

    def removeEntry(entryId: String): State =
      copy(entries = entries.filterNot(_.id == entryId))

    def findEntriesDueNow(now: LocalDateTime): List[Entry] =
      entries.filter(_.dueDate.isBefore(now))
  }

  var state = State(List.empty)
  val eventBusActor = context.actorOf(EventBusPublisherActor.props[EventBus, PublicEvent](scheduleName, timeBucket, eventBus), "eventBusPublisher")
  var eventNrToDeliveryId = Map.empty[Long, Long]

  override def receiveRecover: Receive = {
    case e: EntityEventEnvelope[_] => e.cast[Event] match {
      case Some(envelope) => applyEvent(envelope)
      case None => throw new IllegalArgumentException(s"Unexpected event ${e.event}")
    }
  }

  override def receiveCommand: Receive = {
    case c: Command => handleCommand(c)
  }

  def handleCommand(c: Command): Unit = c match {
    case AddScheduleEntry(_, dueDate, entry) =>
      persist(putInEnvelope(ScheduleEntryAdded(scheduleName, dueDate, entry)))(applyEvent)

    case FireDueEntries =>
      val now = LocalDateTime.now()
      val envelopes = state
        .findEntriesDueNow(now)
        .map(entry => ScheduleEntryFired(scheduleName, entry.id))
        .map(putInEnvelope)
      persistAll(envelopes)(applyEvent)

    case MarkEventAsPublished(eventNr) =>
      eventNrToDeliveryId.get(eventNr).foreach { _ =>
        persistAsync(putInEnvelope(EventPublished(scheduleName, eventNr)))(applyEvent)
      }
  }

  def putInEnvelope(event: Event): EntityEventEnvelope[Event] =
    EntityEventEnvelope(
      MessageId(persistenceId + "-" + lastSequenceNr),
      event,
      Instant.now(),
      MessageId("time")
    )

  def applyEvent(envelope: EntityEventEnvelope[Event]): Unit = envelope.event match {
    case publicEvent: PublicEvent =>
      publicEvent match {
        case ScheduleEntryAdded(_, dueDate, entryId) =>
          state = state.addEntry(entryId, dueDate)
        case ScheduleEntryFired(_, entryId) =>
          state = state.removeEntry(entryId)
      }
      publishEvent(envelope.asInstanceOf[EntityEventEnvelope[PublicEvent]])

    case EventPublished(_, eventNr) =>
      eventNrToDeliveryId.get(eventNr).foreach(confirmDelivery)
      eventNrToDeliveryId = eventNrToDeliveryId - eventNr
  }

  def publishEvent(eventEnvelope: EntityEventEnvelope[PublicEvent]): Unit =
    deliver(eventBusActor.path) { deliveryId =>
      eventNrToDeliveryId = eventNrToDeliveryId + (lastSequenceNr -> deliveryId)
      EventBusPublisherActor.PublishEvent(
        eventEnvelope,
        Message("not-used", MarkEventAsPublished(lastSequenceNr)),
        regionRef
      )
    }
}
