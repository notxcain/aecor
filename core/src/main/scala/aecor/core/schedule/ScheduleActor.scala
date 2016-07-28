package aecor.core.schedule

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.{LocalDateTime, ZoneId}

import aecor.core.actor.{EventsourcedActor, NowOrDeferred}
import aecor.core.actor.NowOrDeferred.Now
import aecor.core.aggregate._
import aecor.core.message.ExtractShardId
import akka.actor.{Actor, ActorRef, Props}
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.{Done, NotUsed}

import scala.concurrent.duration._

object ScheduleActorSupervisor {
  def calculateTimeBucket(date: LocalDateTime, bucketLength: FiniteDuration): Long = {
    date.atZone(ZoneId.systemDefault()).toEpochSecond / bucketLength.toSeconds
  }

  def extractEntityId(bucketLength: FiniteDuration): ExtractEntityId = {
    case c@AddScheduleEntry(scheduleName, entryId, dueDate) =>
      (scheduleName + "-" + calculateTimeBucket(dueDate, bucketLength), c)
  }

  def extractShardId(numberOfShards: Int, bucketLength: FiniteDuration): ExtractShardId = {
    case c@AddScheduleEntry(scheduleName, entryId, dueDate) =>
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

  val worker: ActorRef = context.actorOf(ScheduleActor.props(scheduleName, timeBucket), "worker")
  val tickControl = Source.tick(0.seconds, 1.second, NotUsed).map(_ => FireDueEntries(LocalDateTime.now())).toMat(Sink.actorRef(worker, Done))(Keep.left).run()

  @scala.throws[Exception](classOf[Exception])
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    tickControl.cancel()
    super.preRestart(reason, message)
  }

  override def receive: Receive = {
    case c: AddScheduleEntry => worker.forward(c)
  }
}

sealed trait ScheduleEvent
case class ScheduleEntryAdded(scheduleName: String, entryId: String, dueDate: LocalDateTime) extends ScheduleEvent
case class ScheduleEntryFired(scheduleName: String, entryId: String) extends ScheduleEvent

sealed trait ScheduleCommand
case class AddScheduleEntry(scheduleName: String, entryId: String, dueDate: LocalDateTime) extends ScheduleCommand
private[schedule] case class FireDueEntries(now: LocalDateTime) extends ScheduleCommand

private[aecor] case class ScheduleEntry(id: String, dueDate: LocalDateTime)

private[aecor] case class ScheduleState(name: String, entries: List[ScheduleEntry]) {
  def addEntry(entryId: String, dueDate: LocalDateTime): ScheduleState =
    copy(entries = ScheduleEntry(entryId, dueDate) :: entries)

  def removeEntry(entryId: String): ScheduleState =
    copy(entries = entries.filterNot(_.id == entryId))

  def findEntriesDueTo(date: LocalDateTime): List[ScheduleEntry] =
    entries.filter(_.dueDate.isBefore(date))
}

case class ScheduleBehavior(state: ScheduleState) {
  def handleCommand(command: ScheduleCommand): Vector[ScheduleEvent] = command match {
    case AddScheduleEntry(scheduleName, dueDate, entryId) =>
      Vector(ScheduleEntryAdded(scheduleName, dueDate, entryId))
    case FireDueEntries(now) =>
      state.findEntriesDueTo(now)
      .map(entry => ScheduleEntryFired(state.name, entry.id))
      .toVector
  }
  def applyEvent(event: ScheduleEvent): ScheduleBehavior = event match {
    case ScheduleEntryAdded(scheduleName, entryId, dueDate) =>
      copy(state = state.addEntry(entryId, dueDate))
    case ScheduleEntryFired(scheduleName, entryId) =>
      copy(state = state.removeEntry(entryId))
  }
}

object ScheduleBehavior {
  implicit def behavior: AggregateBehavior.Aux[ScheduleBehavior, ScheduleCommand, Nothing, ScheduleEvent] = new AggregateBehavior[ScheduleBehavior] {
    override type Cmd = ScheduleCommand
    override type Evt = ScheduleEvent
    override type Rjn = Nothing
    override def handleCommand(a: ScheduleBehavior)(command: ScheduleCommand): NowOrDeferred[AggregateDecision[Nothing, ScheduleEvent]] =
      Now(Accept(a.handleCommand(command)))
    override def applyEvent(a: ScheduleBehavior)(e: ScheduleEvent): ScheduleBehavior =
      a.applyEvent(e)
  }
}

private[aecor] object ScheduleState {
  def empty(name: String): ScheduleState = ScheduleState(name, List.empty)
}

object ScheduleActor {
  def props(scheduleName: String, timeBucket: String): Props = {

    val d = new ScheduleActor(scheduleName, timeBucket)
    Props(new ScheduleActor(scheduleName, timeBucket))
  }
}

private[aecor] class ScheduleActor(scheduleName: String, timeBucket: String)
  extends EventsourcedActor[AggregateEventsourcedBehavior[ScheduleBehavior], AggregateCommand[ScheduleCommand], AggregateEvent[ScheduleEvent], AggregateResponse[Nothing]]("Schedule", AggregateEventsourcedBehavior(ScheduleBehavior(ScheduleState.empty(scheduleName))), 60.seconds) {
  override protected val entityId: String = scheduleName + "-" + timeBucket
}
