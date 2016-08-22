package aecor.core.actor

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}

import aecor.core.message._
import akka.NotUsed
import akka.actor.{ActorLogging, Props, ReceiveTimeout, Stash, Status}
import akka.cluster.sharding.ShardRegion
import akka.pattern.pipe
import akka.persistence.journal.Tagged
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

trait EventsourcedBehavior[A, Command[_]] {
  type State
  type Event
  def handleCommand[Response](a: A)(state: State, command: Command[Response]): Future[EventsourcedBehavior.Result[Response, Event]]
  def init(a: A): State
}

trait EventsourcedState[State, Event] {
  def applyEvent(state: State, event: Event): State
}

object EventsourcedBehavior {
  type Aux[A, Command[_], State0, Event0] = EventsourcedBehavior[A, Command] {
    type State = State0
    type Event = Event0
  }

  type Result[Response, Event] = (Response, Seq[Event])
}

sealed trait SnapshotPolicy {
  def shouldSnapshotAtEventCount(eventCount: Long): Boolean
}

object SnapshotPolicy {

  case object Never extends SnapshotPolicy {
    override def shouldSnapshotAtEventCount(eventCount: Long): Boolean = false
  }

  case class After(numberOfEvents: Int) extends SnapshotPolicy {
    override def shouldSnapshotAtEventCount(eventCount: Long): Boolean =
      eventCount % numberOfEvents == 0
  }

}

sealed trait Identity

object Identity {

  case class Provided(value: String) extends Identity

  case object FromPathName extends Identity

}

object EventsourcedActor {
  def props[Behavior, Command[_], State, Event]
  (behavior: Behavior, entityName: String, identity: Identity, snapshotPolicy: SnapshotPolicy, idleTimeout: FiniteDuration)
  (implicit actorBehavior: EventsourcedBehavior.Aux[Behavior, Command, State, Event], projection: EventsourcedState[State, Event], Command: ClassTag[Command[_]], Event: ClassTag[Event], State: ClassTag[State]) =
    Props(new EventsourcedActor[Behavior, Command, State, Event](entityName, behavior, identity, snapshotPolicy, idleTimeout))

  def extractEntityId[A: ClassTag](correlation: A => String): ShardRegion.ExtractEntityId = {
    case a: A ⇒ (correlation(a), a)
  }

  def extractShardId[A: ClassTag](numberOfShards: Int)(correlation: A => String): ShardRegion.ExtractShardId = {
    case a: A => ExtractShardId(correlation(a), numberOfShards)
  }

  case object Stop
}

class EventsourcedActor[Behavior, Command[_], State, Event]
(entityName: String,
 behavior: Behavior,
 identity: Identity,
 snapshotPolicy: SnapshotPolicy,
 idleTimeout: FiniteDuration
)(implicit
  Behavior: EventsourcedBehavior.Aux[Behavior, Command, State, Event],
  State: EventsourcedState[State, Event],
  CommandClass: ClassTag[Command[_]],
  EventClass: ClassTag[Event],
  StateClass: ClassTag[State]
) extends PersistentActor
          with Stash
          with ActorLogging {

  import context.dispatcher

  final private val entityId: String = identity match {
    case Identity.Provided(value) => value
    case Identity.FromPathName => URLDecoder.decode(self.path.name, StandardCharsets.UTF_8.name())
  }

  private case class HandleResult(result: EventsourcedBehavior.Result[Any, Event])

  final override val persistenceId: String = s"$entityName-$entityId"

  protected val tags = Set(entityName)

  private val recoveryStartTimestamp: Instant = Instant.now()

  log.info("[{}] Starting...", persistenceId)

  protected var state: State = Behavior.init(behavior)

  private var eventCount = 0L

  override def receiveRecover: Receive = {
    case e: Event =>
      applyEvent(e)

    case SnapshotOffer(metadata, snapshot: State) =>
      state = snapshot

    case RecoveryCompleted =>
      log.info("[{}] Recovery to version [{}] completed in [{} ms]", persistenceId, lastSequenceNr, Duration.between(recoveryStartTimestamp, Instant.now()).toMillis)
      setIdleTimeout()
  }

  override def receiveCommand: Receive = receivePassivationMessages.orElse(receiveCommandMessage)

  def receiveCommandMessage: Receive = {
    case command if CommandClass.runtimeClass.isAssignableFrom(command.getClass) =>
      handleCommand(command.asInstanceOf[Command[Any]])
    case other =>
      log.warning("[{}] Unknown message [{}]", persistenceId, other)
  }

  def handleCommand(command: Command[Any]) = {
    log.debug("[{}] Received command [{}]", persistenceId, command)
    Behavior.handleCommand(behavior)(state, command).map(HandleResult).pipeTo(self)(sender)
    context.become {
      case HandleResult(result) =>
        runResult(result)
        unstashAll()
        context.become(receiveCommand)
      case failure @ Status.Failure(e) =>
        log.error(e, "[{}] Deferred result failed", persistenceId)
        sender() ! failure
        unstashAll()
        context.become(receiveCommand)
      case _ =>
        stash()
    }

  }

  def runResult(result: EventsourcedBehavior.Result[Any, Event]): Unit = {
    val (response, events) = result
    log.debug("[{}] Command handler result [{}]", persistenceId, result)
    val envelopes = events.map(Tagged(_, tags))
    var shouldSaveSnapshot = false
    persistAll(envelopes) {
      case Tagged(e: Event, _) =>
        applyEvent(e)
        if (snapshotPolicy.shouldSnapshotAtEventCount(eventCount))
          shouldSaveSnapshot = true
    }
    deferAsync(NotUsed) { _ =>
      if (shouldSaveSnapshot)
        saveSnapshot(state)
      sender() ! response
    }
  }

  def applyEvent(event: Event): Unit = {
    state = State.applyEvent(state, event)
    log.debug("[{}] New behavior [{}]", persistenceId, behavior)
    eventCount += 1
  }

  protected def shouldPassivate: Boolean = true

  private def setIdleTimeout(): Unit = {
    log.debug("Setting idle timeout to [{}]", idleTimeout)
    context.setReceiveTimeout(idleTimeout)
  }

  private def receivePassivationMessages: Receive = {
    case ReceiveTimeout ⇒
      if (shouldPassivate) {
        passivate()
      } else {
        setIdleTimeout()
      }
    case EventsourcedActor.Stop ⇒
      context.stop(self)
  }

  private def passivate(): Unit = {
    log.debug("Passivating...")
    context.parent ! ShardRegion.Passivate(EventsourcedActor.Stop)
  }
}