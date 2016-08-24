package aecor.core.schedule

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.{LocalDateTime, ZoneId}

import aecor.core.actor._
import aecor.core.message.Correlation.CorrelationId
import aecor.core.message.ExtractShardId
import akka.actor.{Actor, ActorRef, NotInfluenceReceiveTimeout, Props, Terminated}
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId, Passivate}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.{Done, NotUsed}

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration._

object ScheduleActorSupervisor {
  def calculateTimeBucket(date: LocalDateTime, bucketLength: FiniteDuration): Long = {
    date.atZone(ZoneId.systemDefault()).toEpochSecond / bucketLength.toSeconds
  }

  def extractEntityId(bucketLength: FiniteDuration): ExtractEntityId = {
    case c: AddScheduleEntry =>
      (c.scheduleName + "-" + calculateTimeBucket(c.dueDate, bucketLength), c)
  }

  def extractShardId(numberOfShards: Int, bucketLength: FiniteDuration): ExtractShardId = {
    case c: AddScheduleEntry =>
      ExtractShardId(c.scheduleName + "-" + calculateTimeBucket(c.dueDate, bucketLength), numberOfShards)
  }

  def props(entityName: String): Props = Props(new ScheduleActorSupervisor(entityName))
}

class ScheduleActorSupervisor(entityName: String) extends Actor {

  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(context.system), "ScheduleActorSupervisor")

  val (scheduleName, timeBucket) = {
    val name = URLDecoder.decode(self.path.name, StandardCharsets.UTF_8.name())
    val components = name.split("-")
    (components.dropRight(1).mkString("-"), components.last)
  }

  val worker: ActorRef = context.actorOf(ScheduleActor.props(entityName, scheduleName, timeBucket), "worker")

  val tickControl = Source.tick(0.seconds, 1.second, NotUsed).map(_ => FireDueEntries(scheduleName, LocalDateTime.now())).toMat(Sink.actorRef(worker, Done))(Keep.left).run()

  @scala.throws[Exception](classOf[Exception])
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    tickControl.cancel()
    super.preRestart(reason, message)
  }

  override def receive: Receive = {
    case c: AddScheduleEntry =>
      worker.forward(c)
    case passivate: Passivate =>
      context.parent ! passivate
      context.become(passivating)
  }

  def passivating: Receive = {
    case c: AddScheduleEntry =>
      context.parent ! c
    case EventsourcedEntity.Stop =>
      context.watch(worker)
      worker ! EventsourcedEntity.Stop
    case Terminated(`worker`) =>
      context.stop(self)
  }
}

sealed trait ScheduleEvent {
  def scheduleName: String
}

case class ScheduleCreated(scheduleName: String) extends ScheduleEvent

case class ScheduleEntryAdded(scheduleName: String, entryId: String, correlationId: CorrelationId, payload: Payload, dueDate: LocalDateTime) extends ScheduleEvent

case class ScheduleEntryFired(scheduleName: String, entryId: String, correlationId: CorrelationId, payload: Payload) extends ScheduleEvent

sealed trait ScheduleCommand[_]

case class AddScheduleEntry(scheduleName: String, entryId: String, correlationId: CorrelationId, payload: Payload, dueDate: LocalDateTime) extends ScheduleCommand[Done]

private[schedule] case class FireDueEntries(scheduleName: String, now: LocalDateTime) extends ScheduleCommand[Done] with NotInfluenceReceiveTimeout

case class Payload(value: Option[Array[Byte]]) extends AnyVal

object Payload {
  def empty: Payload = Payload(None)
}

private[aecor] case class ScheduleEntry(id: String, correlationId: CorrelationId, payload: Payload, dueDate: LocalDateTime)

private[aecor] case class ScheduleState(entries: List[ScheduleEntry], initialized: Boolean) {
  def addEntry(entryId: String, correlationId: CorrelationId, payload: Payload, dueDate: LocalDateTime): ScheduleState =
    copy(entries = ScheduleEntry(entryId, correlationId, payload, dueDate) :: entries)

  def removeEntry(entryId: String): ScheduleState =
    copy(entries = entries.filterNot(_.id == entryId))

  def findEntriesDueTo(date: LocalDateTime): List[ScheduleEntry] =
    entries.filter(_.dueDate.isBefore(date))

  def initialize: ScheduleState = copy(initialized = true)

  def applyEvent(event: ScheduleEvent): ScheduleState = event match {
    case ScheduleCreated(scheduleName) =>
      initialize
    case ScheduleEntryAdded(scheduleName, entryId, correlationId, payload, dueDate) =>
      addEntry(entryId, correlationId, payload, dueDate)
    case e: ScheduleEntryFired =>
      removeEntry(e.entryId)
  }
}

private[aecor] object ScheduleState {
  implicit def state = new EventsourcedState[ScheduleState, ScheduleEvent] {
    override def applyEvent(a: ScheduleState, e: ScheduleEvent): ScheduleState =
      a.applyEvent(e)
    override def init: ScheduleState =
      ScheduleState(List.empty, initialized = false)
  }
}

class ScheduleBehavior {
  final def handleCommand[R](state: ScheduleState, command: ScheduleCommand[R]): (R, Vector[ScheduleEvent]) = command match {
    case AddScheduleEntry(scheduleName, entryId, correlationId, payload, dueDate) =>
      if (state.initialized) {
        Done -> Vector(ScheduleEntryAdded(scheduleName, entryId, correlationId, payload, dueDate))
      } else {
        Done -> Vector(ScheduleCreated(scheduleName), ScheduleEntryAdded(scheduleName, entryId, correlationId, payload, dueDate))
      }
    case FireDueEntries(scheduleName, now) =>
      Done -> state.findEntriesDueTo(now).take(100)
      .map(entry => ScheduleEntryFired(scheduleName, entry.id, entry.correlationId, entry.payload))
      .toVector
  }
}

object ScheduleBehavior {
  implicit val behavior = new EventsourcedBehavior[ScheduleBehavior] {
    override type Command[X] = ScheduleCommand[X]
    override type State = ScheduleState
    override type Event = ScheduleEvent

    override def handleCommand[R](a: ScheduleBehavior)(state: ScheduleState, command: ScheduleCommand[R]): Future[(R, Seq[ScheduleEvent])] =
      Future.successful(a.handleCommand(state, command))
  }
}

object ScheduleActor {
  def props(entityName: String, scheduleName: String, timeBucket: String): Props = {
    Props(new ScheduleActor(entityName, scheduleName, timeBucket))
  }
}

class ScheduleActor(entityName: String, scheduleName: String, timeBucket: String) extends EventsourcedEntity[ScheduleBehavior, ScheduleCommand, ScheduleState, ScheduleEvent](entityName, new ScheduleBehavior(), Identity.Provided(scheduleName + "-" + timeBucket), SnapshotPolicy.Never, 10.seconds) {
  override def shouldPassivate: Boolean = state.entries.isEmpty
}
