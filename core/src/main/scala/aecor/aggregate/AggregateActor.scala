package aecor.aggregate

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.{ Duration, Instant }

import aecor.aggregate.SnapshotPolicy.{ EachNumberOfEvents, Never }
import aecor.aggregate.serialization.PersistentDecoder.Result
import aecor.aggregate.serialization.{ PersistentDecoder, PersistentEncoder, PersistentRepr }
import aecor.data.{ Folded, Handler }
import akka.NotUsed
import akka.actor.{ ActorLogging, Props, ReceiveTimeout, Stash }
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

object AggregateActor {

  def props[Command[_], State, Event: PersistentEncoder: PersistentDecoder](
    entityName: String,
    behavior: Command ~> Handler[State, Event, ?],
    snapshotPolicy: SnapshotPolicy[State],
    tagging: Tagging[Event],
    idleTimeout: FiniteDuration
  )(implicit folder: Folder[Folded, Event, State]): Props =
    Props(new AggregateActor(entityName, behavior, snapshotPolicy, tagging, idleTimeout))

  case object Stop
}

/**
  *
  * Actor encapsulating state of event sourced entity behavior [Behavior]
  *
  * @param entityName entity name used as persistence prefix and as a tag for all events
  * @param behavior entity behavior
  * @param snapshotPolicy snapshot policy to use
  * @param idleTimeout - time with no commands after which graceful actor shutdown is initiated
  */
class AggregateActor[Command[_], State, Event: PersistentEncoder: PersistentDecoder] private[aecor] (
  entityName: String,
  behavior: Command ~> Handler[State, Event, ?],
  snapshotPolicy: SnapshotPolicy[State],
  tagger: Tagging[Event],
  idleTimeout: FiniteDuration
)(implicit folder: Folder[Folded, Event, State])
    extends PersistentActor
    with Stash
    with ActorLogging {

  final private val entityId: String =
    URLDecoder.decode(self.path.name, StandardCharsets.UTF_8.name())

  final override val persistenceId: String = s"$entityName-$entityId"

  private val recoveryStartTimestamp: Instant = Instant.now()

  log.info("[{}] Starting...", persistenceId)

  protected var state: State = folder.zero

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
      handleCommand(command.asInstanceOf[Command[_]])
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
      events.map(e => Tagged(PersistentEncoder[Event].encode(e), tagger(e)))

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
    state = folder
      .step(state, event)
      .getOrElse {
        val error = new IllegalStateException(s"Illegal state while applying [$event] to [$state]")
        log.error(error, error.getMessage)
        throw error
      }
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
