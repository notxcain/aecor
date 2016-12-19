package aecor.schedule

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.{LocalDateTime, ZoneId}

import aecor.core.aggregate.AggregateActor.Tagger
import aecor.core.aggregate.Correlation.CorrelationId
import aecor.core.aggregate._
import aecor.core.aggregate.behavior.{Behavior, Handler}
import aecor.schedule.ScheduleEvent.{ScheduleEntryAdded, ScheduleEntryFired}
import akka.actor.{
  Actor,
  ActorRef,
  NotInfluenceReceiveTimeout,
  Props,
  Terminated
}
import akka.cluster.sharding.ShardRegion.{
  ExtractEntityId,
  ExtractShardId,
  Passivate
}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.{Done, NotUsed}
import cats.~>

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.util.hashing.MurmurHash3

object ScheduleActorSupervisor {
  def calculateTimeBucket(date: LocalDateTime,
                          bucketLength: FiniteDuration): Long = {
    date.atZone(ZoneId.systemDefault()).toEpochSecond / bucketLength.toSeconds
  }

  def extractEntityId(bucketLength: FiniteDuration): ExtractEntityId = {
    case c: AddScheduleEntry =>
      (c.scheduleName + "-" + calculateTimeBucket(c.dueDate, bucketLength), c)
  }

  def extractShardId(numberOfShards: Int,
                     bucketLength: FiniteDuration): ExtractShardId = {
    case c: AddScheduleEntry =>
      val id =
        s"${c.scheduleName}-${calculateTimeBucket(c.dueDate, bucketLength)}"
      val shardNumber = scala.math.abs(MurmurHash3.stringHash(id)) % numberOfShards
      shardNumber.toString
  }

  def props(entityName: String, tickInterval: FiniteDuration): Props =
    Props(new ScheduleActorSupervisor(entityName, tickInterval))
}

class ScheduleActorSupervisor(entityName: String, tickInterval: FiniteDuration)
    extends Actor {

  implicit val materializer = ActorMaterializer(
    ActorMaterializerSettings(context.system),
    "ScheduleActorSupervisor")

  val (scheduleName, timeBucket) = {
    val name = URLDecoder.decode(self.path.name, StandardCharsets.UTF_8.name())
    val components = name.split("-")
    (components.dropRight(1).mkString("-"), components.last)
  }

  val worker: ActorRef = context.actorOf(
    ScheduleActor.props(entityName, scheduleName, timeBucket),
    "worker")

  val tickControl = Source
    .tick(0.seconds, tickInterval, NotUsed)
    .map(_ => FireDueEntries(scheduleName, LocalDateTime.now()))
    .toMat(Sink.actorRef(worker, Done))(Keep.left)
    .run()

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
    case AggregateActor.Stop =>
      context.watch(worker)
      worker ! AggregateActor.Stop
    case Terminated(`worker`) =>
      context.stop(self)
  }
}

sealed trait ScheduleEvent {
  def scheduleName: String
}

object ScheduleEvent {
  case class ScheduleEntryAdded(scheduleName: String,
                                entryId: String,
                                correlationId: CorrelationId,
                                dueDate: LocalDateTime)
      extends ScheduleEvent

  case class ScheduleEntryFired(scheduleName: String,
                                entryId: String,
                                correlationId: CorrelationId)
      extends ScheduleEvent
}

sealed trait ScheduleCommand[_]

case class AddScheduleEntry(scheduleName: String,
                            entryId: String,
                            correlationId: CorrelationId,
                            dueDate: LocalDateTime)
    extends ScheduleCommand[Done]

private[schedule] case class FireDueEntries(scheduleName: String,
                                            now: LocalDateTime)
    extends ScheduleCommand[Done]
    with NotInfluenceReceiveTimeout

private[aecor] case class ScheduleEntry(id: String,
                                        correlationId: CorrelationId,
                                        dueDate: LocalDateTime)

private[aecor] case class ScheduleState(entries: List[ScheduleEntry],
                                        ids: Set[String]) {
  def addEntry(entryId: String,
               correlationId: CorrelationId,
               dueDate: LocalDateTime): ScheduleState =
    copy(entries = ScheduleEntry(entryId, correlationId, dueDate) :: entries,
         ids = ids + entryId)

  def removeEntry(entryId: String): ScheduleState =
    copy(entries = entries.filterNot(_.id == entryId))

  def findEntriesDueTo(date: LocalDateTime): List[ScheduleEntry] =
    entries.filter(_.dueDate.isBefore(date))

  def applyEvent(event: ScheduleEvent): ScheduleState = event match {
    case ScheduleEntryAdded(scheduleName, entryId, correlationId, dueDate) =>
      addEntry(entryId, correlationId, dueDate)
    case e: ScheduleEntryFired =>
      removeEntry(e.entryId)
  }
}

object ScheduleBehavior {

  def apply(): Behavior[ScheduleCommand, ScheduleState, ScheduleEvent] =
    Behavior(
      commandHandler =
        new (ScheduleCommand ~> Handler[ScheduleState, ScheduleEvent, ?]) {
          override def apply[A](fa: ScheduleCommand[A])
            : (ScheduleState) => (Seq[ScheduleEvent], A) = { state =>
            fa match {
              case AddScheduleEntry(scheduleName,
                                    entryId,
                                    correlationId,
                                    dueDate) =>
                if (state.ids.contains(entryId)) {
                  Vector.empty -> Done
                } else {
                  Vector(
                    ScheduleEntryAdded(scheduleName,
                                       entryId,
                                       correlationId,
                                       dueDate)) -> Done
                }

              case FireDueEntries(scheduleName, now) =>
                state
                  .findEntriesDueTo(now)
                  .take(100)
                  .map(
                    entry =>
                      ScheduleEntryFired(scheduleName,
                                         entry.id,
                                         entry.correlationId))
                  .toVector -> Done
            }
          }
        },
      initialState = ScheduleState(List.empty, Set.empty),
      projector = _.applyEvent(_)
    )
}

object ScheduleActor {
  def props(entityName: String,
            scheduleName: String,
            timeBucket: String): Props = {
    Props(new ScheduleActor(entityName, scheduleName, timeBucket))
  }
}

class ScheduleActor(entityName: String,
                    scheduleName: String,
                    timeBucket: String)
    extends AggregateActor[ScheduleCommand, ScheduleState, ScheduleEvent](
      entityName,
      ScheduleBehavior(),
      Identity.Provided(scheduleName + "-" + timeBucket),
      SnapshotPolicy.Never,
      Tagger.const(entityName),
      10.seconds) {
  override def shouldPassivate: Boolean = state.entries.isEmpty
}
