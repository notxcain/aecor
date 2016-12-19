package aecor.core.aggregate

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.{ Duration, Instant }

import aecor.core.aggregate.AggregateActor.Tagger
import aecor.core.aggregate.behavior.Behavior
import akka.NotUsed
import akka.actor.{ ActorLogging, Props, ReceiveTimeout, Stash }
import akka.cluster.sharding.ShardRegion
import akka.persistence.journal.Tagged
import akka.persistence.{ PersistentActor, RecoveryCompleted, SnapshotOffer }

import scala.concurrent.duration.FiniteDuration

sealed trait SnapshotPolicy {
  def shouldSnapshotAtEventCount(eventCount: Long): Boolean
}

object SnapshotPolicy {

  case object Never extends SnapshotPolicy {
    override def shouldSnapshotAtEventCount(eventCount: Long): Boolean = false
  }

  case class EachNumberOfEvents(numberOfEvents: Int) extends SnapshotPolicy {
    override def shouldSnapshotAtEventCount(eventCount: Long): Boolean =
      eventCount % numberOfEvents == 0
  }

}

sealed trait Identity
object Identity {
  case class Provided(value: String) extends Identity
  case object FromPathName extends Identity
}

object AggregateActor {
  type Tagger[E] = E => String
  object Tagger {
    def const[E](value: String): Tagger[E] = _ => value
  }

  def props[Command[_], State, Event](behavior: Behavior[Command, State, Event],
                                      entityName: String,
                                      identity: Identity,
                                      snapshotPolicy: SnapshotPolicy,
                                      tagger: Tagger[Event],
                                      idleTimeout: FiniteDuration) =
    Props(new AggregateActor(entityName, behavior, identity, snapshotPolicy, tagger, idleTimeout))

  case object Stop
}

/**
  *
  * Actor encapsulating state of event sourced entity behavior [Behavior]
  *
  * @param entityName entity name used as persistence prefix and as a tag for all events
  * @param behavior entity behavior
  * @param identity describes how to extract entity identifier
  * @param snapshotPolicy snapshot policy to use
  * @param idleTimeout - time with no commands after which graceful actor shutdown is initiated
  */
class AggregateActor[Command[_], State, Event] private[aecor] (
  entityName: String,
  behavior: Behavior[Command, State, Event],
  identity: Identity,
  snapshotPolicy: SnapshotPolicy,
  tagger: Tagger[Event],
  idleTimeout: FiniteDuration
) extends PersistentActor
    with Stash
    with ActorLogging {

  final private val entityId: String = identity match {
    case Identity.Provided(value) => value
    case Identity.FromPathName =>
      URLDecoder.decode(self.path.name, StandardCharsets.UTF_8.name())
  }

  final override val persistenceId: String = s"$entityName-$entityId"

  protected val tags = Set(entityName)

  private val recoveryStartTimestamp: Instant = Instant.now()

  log.info("[{}] Starting...", persistenceId)

  protected var state: State = behavior.initialState

  private var eventCount = 0L

  final override def receiveRecover: Receive = {
    case SnapshotOffer(_, snapshot) =>
      state = snapshot.asInstanceOf[State]

    case RecoveryCompleted =>
      log.info(
        "[{}] Recovery to version [{}] completed in [{} ms]",
        persistenceId,
        lastSequenceNr,
        Duration.between(recoveryStartTimestamp, Instant.now()).toMillis
      )
      setIdleTimeout()

    case e =>
      applyEvent(e.asInstanceOf[Event])
  }

  final override def receiveCommand: Receive =
    receivePassivationMessages.orElse(receiveCommandMessage)

  private def receiveCommandMessage: Receive = {
    case command =>
      handleCommand(command.asInstanceOf[Command[Any]])
  }

  private def handleCommand(command: Command[Any]) = {
    val (events, response) = behavior.commandHandler(command)(state)
    log.debug(
      "[{}] Command [{}] produced response [{}] and events [{}]",
      persistenceId,
      command,
      response,
      events
    )
    val envelopes = events.map(e => Tagged(e, Set(tagger(e))))
    var shouldSaveSnapshot = false
    persistAll(envelopes) { x =>
      applyEvent(x.payload.asInstanceOf[Event])
      if (snapshotPolicy.shouldSnapshotAtEventCount(eventCount))
        shouldSaveSnapshot = true
    }
    deferAsync(NotUsed) { _ =>
      if (shouldSaveSnapshot)
        saveSnapshot(state)
      sender() ! response
    }
  }

  private def applyEvent(event: Event): Unit = {
    state = behavior.projector(state, event)
    if (recoveryFinished) {
      log.debug("[{}] New state [{}]", persistenceId, state)
    }
    eventCount += 1
  }

  private def receivePassivationMessages: Receive = {
    case ReceiveTimeout =>
      if (shouldPassivate) {
        passivate()
      } else {
        setIdleTimeout()
      }
    case AggregateActor.Stop =>
      context.stop(self)
  }

  protected def shouldPassivate: Boolean = true

  private def passivate(): Unit = {
    log.debug("[{}] Passivating...", persistenceId)
    context.parent ! ShardRegion.Passivate(AggregateActor.Stop)
  }

  private def setIdleTimeout(): Unit = {
    log.debug("[{}] Setting idle timeout to [{}]", persistenceId, idleTimeout)
    context.setReceiveTimeout(idleTimeout)
  }
}
