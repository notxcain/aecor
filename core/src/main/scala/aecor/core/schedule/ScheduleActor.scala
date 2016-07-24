package aecor.core.schedule

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.{Instant, LocalDateTime, ZoneId}

import aecor.core.aggregate.{AggregateEventEnvelope, CommandId, EventId, HandleCommand}
import aecor.core.message.ExtractShardId
import aecor.util._
import akka.actor.{Actor, ActorLogging, Props}
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId}
import akka.persistence.PersistentActor
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.{Done, NotUsed}

import scala.concurrent.duration._

object ScheduleActorSupervisor {
  def calculateTimeBucket(date: LocalDateTime, bucketLength: FiniteDuration): Long = {
    date.atZone(ZoneId.systemDefault()).toEpochSecond / bucketLength.toSeconds
  }

  def extractEntityId(bucketLength: FiniteDuration): ExtractEntityId = {
    case c@AddScheduleEntry(scheduleName, dueDate, entryId) =>
      (scheduleName + "-" + calculateTimeBucket(dueDate, bucketLength), c)
  }

  def extractShardId(numberOfShards: Int, bucketLength: FiniteDuration): ExtractShardId = {
    case c@AddScheduleEntry(scheduleName, dueDate, entryId) =>
      ExtractShardId(scheduleName + "-" + calculateTimeBucket(dueDate, bucketLength), numberOfShards)
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

  val worker = context.actorOf(ScheduleActor.props(scheduleName, timeBucket), scheduleName + "-" + timeBucket)
  val tickControl = Source.tick(0.seconds, 1.second, NotUsed).map(_ => FireDueEntries(LocalDateTime.now())).toMat(Sink.actorRef(worker, Done))(Keep.left).run()

  @scala.throws[Exception](classOf[Exception])
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    tickControl.cancel()
    super.preRestart(reason, message)
  }

  override def receive: Receive = {
    case hc @ HandleCommand(id, c: AddScheduleEntry) => worker.forward(hc)
  }
}

sealed trait ScheduleActorEvent
case class ScheduleEntryAdded(scheduleName: String, dueDate: LocalDateTime, entryId: String) extends ScheduleActorEvent
case class ScheduleEntryFired(scheduleName: String, entryId: String) extends ScheduleActorEvent

sealed trait ScheduleActorCommand
case class AddScheduleEntry(scheduleName: String, dueDate: LocalDateTime, entryId: String) extends ScheduleActorCommand
private[schedule] case class FireDueEntries(now: LocalDateTime) extends ScheduleActorCommand

object ScheduleActor {
  def props(scheduleName: String, timeBucket: String): Props =
    Props(new ScheduleActor(scheduleName, timeBucket))
}

private[aecor] case class ScheduleEntry(id: String, dueDate: LocalDateTime)

private[aecor] case class ScheduleActorState(entries: List[ScheduleEntry]) {
  def addEntry(entryId: String, dueDate: LocalDateTime): ScheduleActorState =
    copy(entries = ScheduleEntry(entryId, dueDate) :: entries)

  def removeEntry(entryId: String): ScheduleActorState =
    copy(entries = entries.filterNot(_.id == entryId))

  def findEntriesDueTo(date: LocalDateTime): List[ScheduleEntry] =
    entries.filter(_.dueDate.isBefore(date))
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
    case c: ScheduleActorCommand => handleCommand(c)
  }

  def handleCommand(c: ScheduleActorCommand): Unit = c match {
    case AddScheduleEntry(_, dueDate, entry) =>
      persist(putInEnvelope(ScheduleEntryAdded(scheduleName, dueDate, entry)))(applyEvent)

    case FireDueEntries(now) =>
      val envelopes = state
                      .findEntriesDueTo(now)
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
