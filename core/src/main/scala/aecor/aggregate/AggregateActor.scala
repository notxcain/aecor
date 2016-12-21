package aecor.aggregate

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.{ Duration, Instant }

import aecor.aggregate.AggregateActor.Tagger
import aecor.aggregate.SnapshotPolicy.{ EachNumberOfEvents, Never }
import aecor.behavior.Behavior
import aecor.serialization.PersistentDecoder.Result
import aecor.serialization.akka.PersistentRepr
import aecor.serialization.{ PersistentDecoder, PersistentEncoder }
import akka.NotUsed
import akka.actor.{ ActorLogging, Props, ReceiveTimeout, Stash }
import akka.cluster.sharding.ShardRegion
import akka.persistence.journal.Tagged
import akka.persistence.{ PersistentActor, RecoveryCompleted, SnapshotOffer }

import scala.concurrent.duration.FiniteDuration
import scala.util.{ Left, Right }

sealed trait SnapshotPolicy[+E]

object SnapshotPolicy {
  def never[E]: SnapshotPolicy[E] = Never.asInstanceOf[SnapshotPolicy[E]]

  def eachNumberOfEvents[E: PersistentEncoder: PersistentDecoder](
    numberOfEvents: Int
  ): SnapshotPolicy[E] = EachNumberOfEvents(numberOfEvents)

  private[aggregate] case object Never extends SnapshotPolicy[Nothing]

  private[aggregate] case class EachNumberOfEvents[State: PersistentEncoder: PersistentDecoder](
    numberOfEvents: Int
  ) extends SnapshotPolicy[State] {
    def encode(state: State): PersistentRepr = PersistentEncoder[State].encode(state)
    def decode(repr: PersistentRepr): Result[State] = PersistentDecoder[State].decode(repr)
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

  def props[Command[_], State, Event: PersistentEncoder: PersistentDecoder](
    behavior: Behavior[Command, State, Event],
    entityName: String,
    identity: Identity,
    snapshotPolicy: SnapshotPolicy[State],
    tagger: Tagger[Event],
    idleTimeout: FiniteDuration
  ) =
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
class AggregateActor[Command[_], State, Event: PersistentEncoder: PersistentDecoder] private[aecor] (
  entityName: String,
  behavior: Behavior[Command, State, Event],
  identity: Identity,
  snapshotPolicy: SnapshotPolicy[State],
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
    case repr: PersistentRepr =>
      PersistentDecoder[Event].decode(repr) match {
        case Left(cause) =>
          onRecoveryFailure(cause, Some(repr))
        case Right(event) =>
          applyEvent(event)
      }

    case SnapshotOffer(_, snapshotRepr: PersistentRepr) =>
      snapshotPolicy match {
        case Never => ()
        case e @ EachNumberOfEvents(_) =>
          e.decode(snapshotRepr) match {
            case Left(cause) =>
              onRecoveryFailure(cause, Some(snapshotRepr))
            case Right(snapshot) =>
              state = snapshot
          }
      }

    case RecoveryCompleted =>
      log.info(
        "[{}] Recovery to version [{}] completed in [{} ms]",
        persistenceId,
        lastSequenceNr,
        Duration.between(recoveryStartTimestamp, Instant.now()).toMillis
      )
      setIdleTimeout()
  }

  final override def receiveCommand: Receive =
    receivePassivationMessages.orElse(receiveCommandMessage)

  private def receiveCommandMessage: Receive = {
    case command =>
      handleCommand(command.asInstanceOf[Command[Any]])
  }

  private def handleCommand(command: Command[Any]) = {
    val (events, reply) = behavior.commandHandler(command)(state)
    log.debug(
      "[{}] Command [{}] produced reply [{}] and events [{}]",
      persistenceId,
      command,
      reply,
      events
    )
    val envelopes =
      events.map(e => Tagged(PersistentEncoder[Event].encode(e), Set(tagger(e))))

    persistAll(envelopes)(_ => ())

    deferAsync(NotUsed) { _ =>
      events.foreach { event =>
        applyEvent(event)
        snapshotIfNeeded()
      }
      sender() ! reply
    }
  }

  private def snapshotIfNeeded(): Unit =
    snapshotPolicy match {
      case e @ EachNumberOfEvents(numberOfEvents) if eventCount % numberOfEvents == 0 =>
        saveSnapshot(e.encode(state))
      case _ => ()
    }

  private def applyEvent(event: Event): Unit = {
    state = behavior.projector(state, event)
    eventCount += 1
    if (recoveryFinished)
      log.debug("[{}] State [{}]", persistenceId, state)
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
