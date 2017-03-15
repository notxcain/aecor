package aecor.aggregate

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.{ Duration, Instant }

import aecor.aggregate.AggregateActor.HandleCommand
import aecor.aggregate.SnapshotPolicy.{ EachNumberOfEvents, Never }
import aecor.aggregate.serialization.PersistentDecoder.Result
import aecor.aggregate.serialization.{ PersistentDecoder, PersistentEncoder, PersistentRepr }
import aecor.data.{ Folded, Handler }
import akka.actor.{ ActorLogging, Props, ReceiveTimeout }
import akka.cluster.sharding.ShardRegion
import akka.persistence.journal.Tagged
import akka.persistence.{ PersistentActor, RecoveryCompleted, SnapshotOffer }
import cats.~>

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

  def props[Command[_], State, Event: PersistentEncoder: PersistentDecoder](
    entityName: String,
    behavior: Command ~> Handler[State, Event, ?],
    identity: Identity,
    snapshotPolicy: SnapshotPolicy[State],
    tagging: Tagging[Event],
    idleTimeout: FiniteDuration
  )(implicit folder: Folder[Folded, Event, State]): Props =
    Props(new AggregateActor(entityName, behavior, identity, snapshotPolicy, tagging, idleTimeout))

  case class HandleCommand[C[_], A](command: C[A])
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
  behavior: Command ~> Handler[State, Event, ?],
  identity: Identity,
  snapshotPolicy: SnapshotPolicy[State],
  tagger: Tagging[Event],
  idleTimeout: FiniteDuration
)(implicit folder: Folder[Folded, Event, State])
    extends PersistentActor
    with ActorLogging {

  final private val entityId: String = identity match {
    case Identity.Provided(value) => value
    case Identity.FromPathName =>
      URLDecoder.decode(self.path.name, StandardCharsets.UTF_8.name())
  }

  private val eventEncoder = PersistentEncoder[Event]
  private val eventDecoder = PersistentDecoder[Event]

  final override val persistenceId: String = s"$entityName-$entityId"

  private val recoveryStartTimestamp: Instant = Instant.now()

  log.info("[{}] Starting...", persistenceId)

  protected var state: State = folder.zero

  private var eventCount = 0L
  private var snapshotPending = false

  final override def receiveRecover: Receive = {
    case repr: PersistentRepr =>
      eventDecoder.decode(repr) match {
        case Left(cause) =>
          onRecoveryFailure(cause, Some(repr))
        case Right(event) =>
          applyEvent(event)
          eventCount += 1
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

  final override def receiveCommand: Receive = {
    case HandleCommand(command) =>
      handleCommand(command.asInstanceOf[Command[_]])
    case ReceiveTimeout =>
      passivate()
    case AggregateActor.Stop =>
      context.stop(self)
  }

  private def handleCommand(command: Command[_]): Unit = {
    val (events, reply) = behavior(command).run(state)
    log.debug(
      "[{}] Command [{}] produced reply [{}] and events [{}]",
      persistenceId,
      command,
      reply,
      events
    )

    val envelopes =
      events.map(e => Tagged(eventEncoder.encode(e), tagger(e)))

    events.foreach(applyEvent)

    var unpersistedEventCount = events.size
    persistAll(envelopes) { _ =>
      unpersistedEventCount -= 1
      eventCount += 1
      markSnapshotAsPendingIfNeeded()
      if (unpersistedEventCount == 0) {
        sender() ! reply
        snapshotIfPending()
      }
    }
  }

  private def applyEvent(event: Event): Unit = {
    state = folder
      .fold(state, event)
      .getOrElse {
        val error = new IllegalStateException(s"Illegal state after applying [$event] to [$state]")
        log.error(error, error.getMessage)
        throw error
      }
    if (recoveryFinished)
      log.debug("[{}] Current state [{}]", persistenceId, state)
  }

  private def markSnapshotAsPendingIfNeeded(): Unit =
    snapshotPolicy match {
      case e @ EachNumberOfEvents(numberOfEvents) if eventCount % numberOfEvents == 0 =>
        snapshotPending = true
      case _ => ()
    }

  private def snapshotIfPending(): Unit =
    snapshotPolicy match {
      case e @ EachNumberOfEvents(_) if snapshotPending =>
        saveSnapshot(e.encode(state))
        snapshotPending = false
      case _ => ()
    }

  private def passivate(): Unit = {
    log.debug("[{}] Passivating...", persistenceId)
    context.parent ! ShardRegion.Passivate(AggregateActor.Stop)
  }

  private def setIdleTimeout(): Unit = {
    log.debug("[{}] Setting idle timeout to [{}]", persistenceId, idleTimeout)
    context.setReceiveTimeout(idleTimeout)
  }
}
