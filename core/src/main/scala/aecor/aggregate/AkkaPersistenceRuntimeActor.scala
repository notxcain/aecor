package aecor.aggregate

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.{ Duration, Instant }
import java.util.UUID

import aecor.aggregate.AkkaPersistenceRuntimeActor.HandleCommand
import aecor.aggregate.SnapshotPolicy.{ EachNumberOfEvents, Never }
import aecor.aggregate.serialization.PersistentDecoder.Result
import aecor.aggregate.serialization.{ PersistentDecoder, PersistentEncoder, PersistentRepr }
import aecor.data.{ Folded, Handler }
import aecor.effect.Async
import Async.ops._
import akka.actor.{ ActorLogging, Props, ReceiveTimeout, Stash, Status }
import akka.cluster.sharding.ShardRegion
import akka.persistence.journal.Tagged
import akka.persistence.{ PersistentActor, RecoveryCompleted, SnapshotOffer }
import cats.{ Functor, ~> }
import cats.implicits._

import scala.concurrent.duration.FiniteDuration
import scala.util.{ Left, Right }
import scala.collection.immutable.Seq
import akka.pattern.pipe

sealed abstract class SnapshotPolicy[+E] extends Product with Serializable

object SnapshotPolicy {
  def never[E]: SnapshotPolicy[E] = Never.asInstanceOf[SnapshotPolicy[E]]

  def eachNumberOfEvents[E: PersistentEncoder: PersistentDecoder](
    numberOfEvents: Int
  ): SnapshotPolicy[E] = EachNumberOfEvents(numberOfEvents)

  private[aggregate] case object Never extends SnapshotPolicy[Nothing]

  private[aggregate] final case class EachNumberOfEvents[
    State: PersistentEncoder: PersistentDecoder
  ](numberOfEvents: Int)
      extends SnapshotPolicy[State] {
    def encode(state: State): PersistentRepr = PersistentEncoder[State].encode(state)
    def decode(repr: PersistentRepr): Result[State] = PersistentDecoder[State].decode(repr)
  }

}

object AkkaPersistenceRuntimeActor {

  def props[F[_]: Async: Functor, Command[_], State, Event: PersistentEncoder: PersistentDecoder](
    entityName: String,
    behavior: Command ~> Handler[F, State, Seq[Event], ?],
    snapshotPolicy: SnapshotPolicy[State],
    tagging: Tagging[Event],
    idleTimeout: FiniteDuration
  )(implicit folder: Folder[Folded, Event, State]): Props =
    Props(
      new AkkaPersistenceRuntimeActor(entityName, behavior, snapshotPolicy, tagging, idleTimeout)
    )

  final case class HandleCommand[C[_], A](command: C[A])
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
final class AkkaPersistenceRuntimeActor[F[_]: Async: Functor, Op[_], State, Event: PersistentEncoder: PersistentDecoder] private[aecor] (
  entityName: String,
  behavior: Op ~> Handler[F, State, Seq[Event], ?],
  snapshotPolicy: SnapshotPolicy[State],
  tagger: Tagging[Event],
  idleTimeout: FiniteDuration
)(implicit folder: Folder[Folded, Event, State])
    extends PersistentActor
    with ActorLogging
    with Stash {

  import context.dispatcher

  case class CommandResult[A](opId: UUID, events: Seq[Event], reply: A)

  private val entityId: String =
    URLDecoder.decode(self.path.name, StandardCharsets.UTF_8.name())

  private val eventEncoder = PersistentEncoder[Event]
  private val eventDecoder = PersistentDecoder[Event]

  override val persistenceId: String = s"$entityName-$entityId"

  private val recoveryStartTimestamp: Instant = Instant.now()

  log.info("[{}] Starting...", persistenceId)

  protected var state: State = folder.zero

  private var eventCount = 0L
  private var snapshotPending = false

  private def recover(repr: PersistentRepr): Unit =
    eventDecoder.decode(repr) match {
      case Left(cause) =>
        onRecoveryFailure(cause, Some(repr))
      case Right(event) =>
        log.debug("[{}] Recovering [{}]", persistenceId, event)
        applyEvent(event)
        eventCount += 1
    }
  override def receiveRecover: Receive = {
    case repr: PersistentRepr =>
      recover(repr)

    case Tagged(repr: PersistentRepr, _) =>
      recover(repr)

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

    case other =>
      throw new IllegalStateException(s"Unexpected message during recovery [$other]")
  }

  final override def receiveCommand: Receive = {
    case HandleCommand(command) =>
      handleCommand(command.asInstanceOf[Op[_]])
    case ReceiveTimeout =>
      passivate()
    case AkkaPersistenceRuntimeActor.Stop =>
      context.stop(self)
    case CommandResult(opId, events, reply) =>
      log.debug(
        "[{}] Received result of unknown command invocation [{}], ignoring reply [{}] and events [{}]",
        persistenceId,
        opId,
        reply,
        events
      )
  }

  private def handleCommand(command: Op[_]): Unit = {
    val opId = UUID.randomUUID()
    behavior(command)
      .run(state)
      .map {
        case (events, reply) =>
          CommandResult(opId, events, reply)
      }
      .unsafeRun
      .pipeTo(self)(sender)
    context.become {
      case CommandResult(`opId`, events, reply) =>
        log.debug(
          "[{}] Command [{}] produced reply [{}] and events [{}]",
          persistenceId,
          command,
          reply,
          events
        )
        handleCommandResult(events, reply)
        unstashAll()
        context.become(receiveCommand)
      case Status.Failure(e) =>
        sender() ! Status.Failure(e)
        unstashAll()
        context.become(receiveCommand)
      case _ =>
        stash()
    }
  }

  private def handleCommandResult[A](events: Seq[Event], reply: A): Unit =
    if (events.isEmpty) {
      sender() ! reply
    } else {
      val envelopes =
        events.map(e => Tagged(eventEncoder.encode(e), tagger(e).map(_.value)))

      events.foreach(applyEvent)

      var unpersistedEventCount = events.size
      if (unpersistedEventCount == 1) {
        persist(envelopes.head) { _ =>
          sender() ! reply
          eventCount += 1
          markSnapshotAsPendingIfNeeded()
          snapshotIfPending()
        }
      } else {
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
    }

  private def applyEvent(event: Event): Unit = {
    state = folder
      .step(state, event)
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
      case EachNumberOfEvents(numberOfEvents) if eventCount % numberOfEvents == 0 =>
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
    context.parent ! ShardRegion.Passivate(AkkaPersistenceRuntimeActor.Stop)
  }

  private def setIdleTimeout(): Unit = {
    log.debug("[{}] Setting idle timeout to [{}]", persistenceId, idleTimeout)
    context.setReceiveTimeout(idleTimeout)
  }
}
