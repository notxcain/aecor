package aecor.core.scheduler

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.{Instant, LocalDateTime, ZoneId}

import aecor.core.aggregate.{AggregateEventEnvelope, CommandId, EventId}
import aecor.core.message.ExtractShardId
import aecor.core.scheduler.ScheduleActor._
import aecor.util._
import akka.Done
import akka.actor.{Actor, ActorLogging, Cancellable, Props}
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId}
import akka.persistence.PersistentActor
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}

import scala.concurrent.duration._

object ScheduleActorSupervisor {
  def calculateTimeBucket(date: LocalDateTime, bucketLength: Duration = 60.seconds): Long = {
    date.atZone(ZoneId.systemDefault()).toEpochSecond / bucketLength.toSeconds
  }

  def extractEntityId: ExtractEntityId = {
    case c@AddScheduleEntry(scheduleName, dueDate, entryId) =>
      (scheduleName + "-" + calculateTimeBucket(dueDate), c)
  }

  def extractShardId: ExtractShardId = {
    case c@AddScheduleEntry(scheduleName, dueDate, entryId) =>
      ExtractShardId(scheduleName + "-" + calculateTimeBucket(dueDate), 100)
  }

  def props: Props =
    Props(new ScheduleActorSupervisor())
}

class ScheduleActorSupervisor extends Actor {

  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(context.system), "ScheduleActorSupervisor")

  val (scheduleName, timeBucket) = {
    val name = URLDecoder.decode(self.path.name, StandardCharsets.UTF_8.name())
    val components = name.split("-")
    (components.dropRight(1).mkString("-"), components.last)
  }

  var tickControl: Option[Cancellable] = None
  var worker = context.actorOf(ScheduleActor.props(scheduleName, timeBucket), "worker")

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

sealed trait ScheduleActorEvent

case class ScheduleEntryAdded(scheduleName: String, dueDate: LocalDateTime, entryId: String) extends ScheduleActorEvent

case class ScheduleEntryFired(scheduleName: String, entryId: String) extends ScheduleActorEvent

object ScheduleActor {

  sealed trait Command

  case class AddScheduleEntry(scheduleName: String, dueDate: LocalDateTime, entryId: String) extends Command

  private[scheduler] case object FireDueEntries extends Command

  def props(scheduleName: String, timeBucket: String): Props =
    Props(new ScheduleActor(scheduleName, timeBucket))
}

private[aecor] case class ScheduleEntry(id: String, dueDate: LocalDateTime)

private[aecor] case class ScheduleActorState(entries: List[ScheduleEntry]) {
  def addEntry(entryId: String, dueDate: LocalDateTime): ScheduleActorState =
    copy(entries = ScheduleEntry(entryId, dueDate) :: entries)

  def removeEntry(entryId: String): ScheduleActorState =
    copy(entries = entries.filterNot(_.id == entryId))

  def findEntriesDueNow(now: LocalDateTime): List[ScheduleEntry] =
    entries.filter(_.dueDate.isBefore(now))
}

private[aecor] object ScheduleActorState {
  def empty: ScheduleActorState = ScheduleActorState(List.empty)
}

private[aecor] class ScheduleActor(scheduleName: String, timeBucket: String)
  extends PersistentActor
          with ActorLogging {

  override val persistenceId: String = "Schedule-" + scheduleName + "-" + timeBucket

  private var state = ScheduleActorState.empty

  override def receiveRecover: Receive = {
    case e: AggregateEventEnvelope[_] => e.cast[ScheduleActorEvent] match {
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
  }

  def putInEnvelope(event: ScheduleActorEvent): AggregateEventEnvelope[ScheduleActorEvent] =
    AggregateEventEnvelope(
                            generate[EventId],
                            event,
                            Instant.now(),
                            CommandId(s"time-${LocalDateTime.now()}")
                          )

  def applyEvent(envelope: AggregateEventEnvelope[ScheduleActorEvent]): Unit = envelope.event match {
    case publicEvent: ScheduleActorEvent =>
      publicEvent match {
        case ScheduleEntryAdded(_, dueDate, entryId) =>
          state = state.addEntry(entryId, dueDate)
        case ScheduleEntryFired(_, entryId) =>
          state = state.removeEntry(entryId)
      }
  }
}
