package aecor.core.schedule

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.{LocalDateTime, ZoneId}

import aecor.core.actor.NowOrDeferred.Now
import aecor.core.actor.{EventsourcedActor, EventsourcedBehavior, NowOrDeferred}
import aecor.core.message.Correlation.CorrelationId
import aecor.core.message.{ExtractShardId, Passivation}
import akka.actor.{Actor, ActorRef, NotInfluenceReceiveTimeout, Props, Terminated}
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId, Passivate}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.{Done, NotUsed}

import scala.collection.immutable.Seq
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

  val tickControl = Source.tick(0.seconds, 1.second, NotUsed).map(_ => FireDueEntries(LocalDateTime.now())).toMat(Sink.actorRef(worker, Done))(Keep.left).run()

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
    // ignore
    case Passivation.Stop =>
      context.watch(worker)
      worker ! Passivation.Stop
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

sealed trait ScheduleCommand

case class AddScheduleEntry(scheduleName: String, entryId: String, correlationId: CorrelationId, payload: Payload, dueDate: LocalDateTime) extends ScheduleCommand

private[schedule] case class FireDueEntries(now: LocalDateTime) extends ScheduleCommand with NotInfluenceReceiveTimeout

case class Payload(value: Option[Array[Byte]]) extends AnyVal

object Payload {
  def empty: Payload = Payload(None)
}

private[aecor] case class ScheduleEntry(id: String, correlationId: CorrelationId, payload: Payload, dueDate: LocalDateTime)

private[aecor] case class ScheduleState(name: String, entries: List[ScheduleEntry], initialized: Boolean) {
  def addEntry(entryId: String, correlationId: CorrelationId, payload: Payload, dueDate: LocalDateTime): ScheduleState =
    copy(entries = ScheduleEntry(entryId, correlationId, payload, dueDate) :: entries)

  def removeEntry(entryId: String): ScheduleState =
    copy(entries = entries.filterNot(_.id == entryId))

  def findEntriesDueTo(date: LocalDateTime): List[ScheduleEntry] =
    entries.filter(_.dueDate.isBefore(date))

  def initialize: ScheduleState = copy(initialized = true)
}

private[aecor] object ScheduleState {
  def empty(name: String): ScheduleState = ScheduleState(name, List.empty, initialized = false)
}

case class ScheduleBehavior(state: ScheduleState) {
  def handleCommand(command: ScheduleCommand): Vector[ScheduleEvent] = command match {
    case AddScheduleEntry(scheduleName, entryId, correlationId, payload, dueDate) =>
      if (state.initialized) {
        Vector(ScheduleEntryAdded(scheduleName, entryId, correlationId, payload, dueDate))
      } else {
        Vector(ScheduleCreated(scheduleName), ScheduleEntryAdded(scheduleName, entryId, correlationId, payload, dueDate))
      }
    case FireDueEntries(now) =>
      state.findEntriesDueTo(now).take(100)
      .map(entry => ScheduleEntryFired(state.name, entry.id, entry.correlationId, entry.payload))
      .toVector
  }

  def applyEvent(event: ScheduleEvent): ScheduleBehavior = copy(
    state = event match {
      case ScheduleCreated(scheduleName) =>
        state.initialize
      case ScheduleEntryAdded(scheduleName, entryId, correlationId, payload, dueDate) =>
        state.addEntry(entryId, correlationId, payload, dueDate)
      case e: ScheduleEntryFired =>
        state.removeEntry(e.entryId)
    }
  )
}

object ScheduleBehavior {
  implicit val eventsourcedBehavior = new EventsourcedBehavior[ScheduleBehavior] {
    override type Command = ScheduleCommand
    override type Response = Done
    override type Event = ScheduleEvent

    override def handleCommand(a: ScheduleBehavior)(command: ScheduleCommand): NowOrDeferred[(Done, Seq[ScheduleEvent])] =
      Now(Done -> a.handleCommand(command))

    override def applyEvent(a: ScheduleBehavior)(event: ScheduleEvent): ScheduleBehavior =
      a.applyEvent(event)
  }
}

object ScheduleActor {
  def props(entityName: String, scheduleName: String, timeBucket: String): Props = {
    Props(new ScheduleActor(entityName, scheduleName, timeBucket))
  }
}

class ScheduleActor(entityName: String, scheduleName: String, timeBucket: String)
  extends EventsourcedActor[ScheduleBehavior, ScheduleCommand, ScheduleEvent, Done](entityName, ScheduleBehavior(ScheduleState.empty(scheduleName)), 10.seconds) {
  override protected def entityId: String = scheduleName + "-" + timeBucket

  override def shouldPassivate: Boolean = behavior.state.entries.isEmpty
}
