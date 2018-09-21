package aecor.runtime.akkapersistence

import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}
import java.util.UUID

import aecor.data.Folded.{Impossible, Next}
import aecor.data._
import aecor.encoding.{KeyDecoder, WireProtocol}
import aecor.runtime.akkapersistence.AkkaPersistenceRuntimeActor.{CommandResult, HandleCommand}
import aecor.runtime.akkapersistence.SnapshotPolicy.{EachNumberOfEvents, Never}
import aecor.runtime.akkapersistence.serialization.{Message, PersistentDecoder, PersistentEncoder, PersistentRepr}
import aecor.util.effect._
import akka.actor.{ActorLogging, Props, ReceiveTimeout, Stash, Status}
import akka.cluster.sharding.ShardRegion
import akka.pattern.pipe
import akka.persistence.journal.Tagged
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}
import cats.data.Chain
import cats.effect.Effect
import cats.implicits._
import io.aecor.liberator.Invocation

import scala.concurrent.duration.FiniteDuration
import scala.util.{Left, Right}

private[akkapersistence] object AkkaPersistenceRuntimeActor {

  val PersistenceIdSeparator: String = "-"

  def props[M[_[_]], F[_]: Effect, I: KeyDecoder, State, Event: PersistentEncoder: PersistentDecoder](
    entityName: String,
    actions: M[ActionT[F, State, Event, ?]],
    initialState: State,
    updateState: (State, Event) => Folded[State],
    snapshotPolicy: SnapshotPolicy[State],
    tagging: Tagging[I],
    idleTimeout: FiniteDuration,
    journalPluginId: String,
    snapshotPluginId: String
  )(implicit M: WireProtocol[M]): Props =
    Props(
      new AkkaPersistenceRuntimeActor(
        entityName,
        actions,
        initialState,
        updateState,
        snapshotPolicy,
        tagging,
        idleTimeout,
        journalPluginId,
        snapshotPluginId
      )
    )

  final case class HandleCommand(commandBytes: ByteBuffer) extends Message
  final case class CommandResult(resultBytes: ByteBuffer) extends Message
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
private[akkapersistence] final class AkkaPersistenceRuntimeActor[M[_[_]], F[_], I: KeyDecoder, State, Event: PersistentEncoder: PersistentDecoder](
  entityName: String,
  actions: M[ActionT[F, State, Event, ?]],
  initialState: State,
  updateState: (State, Event) => Folded[State],
  snapshotPolicy: SnapshotPolicy[State],
  tagger: Tagging[I],
  idleTimeout: FiniteDuration,
  override val journalPluginId: String,
  override val snapshotPluginId: String
)(implicit M: WireProtocol[M], F: Effect[F])
    extends PersistentActor
    with ActorLogging
    with Stash {

  import context.dispatcher

  private case class ActionResult(opId: UUID, events: Chain[Event], resultBytes: ByteBuffer)

  private val idString: String =
    URLDecoder.decode(self.path.name, StandardCharsets.UTF_8.name())

  private val id: I = KeyDecoder[I]
    .decode(idString)
    .getOrElse(throw new IllegalArgumentException(s"Failed to decode entity id from [$idString]"))

  private val eventEncoder = PersistentEncoder[Event]

  private val eventDecoder = PersistentDecoder[Event]

  override val persistenceId: String =
    s"$entityName${AkkaPersistenceRuntimeActor.PersistenceIdSeparator}$idString"

  private val recoveryStartTimestamp: Instant = Instant.now()

  log.info("[{}] Starting...", persistenceId)

  private var state: State = initialState

  private var eventCount = 0L

  private var snapshotPending = false

  private def recover(repr: PersistentRepr): Unit =
    eventDecoder.decode(repr) match {
      case Left(cause) =>
        onRecoveryFailure(cause, Some(repr))
      case Right(event) =>
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
        case e @ EachNumberOfEvents(_, _) =>
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

  override def receiveCommand: Receive = {
    case HandleCommand(command) =>
      handleCommand(command)
    case ReceiveTimeout =>
      passivate()
    case AkkaPersistenceRuntimeActor.Stop =>
      context.stop(self)
    case ActionResult(opId, _, _) =>
      log.warning(
        "[{}] Received a result of unknown command invocation [{}], ignoring",
        persistenceId,
        opId
      )
  }

  private def handleCommand(commandBytes: ByteBuffer): Unit =
    M.decoder.decode(commandBytes) match {
      case Right(pair) =>
        performInvocation(pair.first, pair.second)
      case Left(decodingFailure) =>
        log.error(new IllegalArgumentException(decodingFailure.getMessage, decodingFailure), "Failed to decode command")
        sender() ! Status.Failure(decodingFailure)
    }

  def performInvocation[A](invocation: Invocation[M, A],
                           resultEncoder: WireProtocol.Encoder[A]): Unit = {
    val opId = UUID.randomUUID()
    invocation
      .invoke(actions).run(state, updateState)
      .flatMap {
        case Next((events, result)) =>
          F.delay(log.info(
            "[{}] Command [{}] produced reply [{}] and events [{}]",
            persistenceId,
            invocation,
            result,
            events
          )) as ActionResult(opId, events, resultEncoder.encode(result))
        case Impossible =>
          val error = new IllegalStateException(s"[$persistenceId] Command [$invocation] produced illegal fold")
          F.delay(log.error(error,
            "[{}] Command [{}] produced illegal fold",
            persistenceId,
            invocation
          )) >>
            F.raiseError[ActionResult](error)
      }
      .unsafeToFuture()
      .pipeTo(self)(sender)
    context.become {
      case ActionResult(`opId`, events, resultBytes) =>
        handleCommandResult(events, CommandResult(resultBytes))
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

  private def handleCommandResult[A](events: Chain[Event], response: CommandResult): Unit =
    if (events.isEmpty) {
      sender() ! response
    } else {
      val envelopes =
        events.map(e => Tagged(eventEncoder.encode(e), tagger.tag(id).map(_.value))).toVector

      events.iterator.foreach(applyEvent)

      var unpersistedEventCount = events.size
      if (unpersistedEventCount == 1) {
        persist(envelopes.head) { _ =>
          sender() ! response
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
            sender() ! response
            snapshotIfPending()
          }
        }
      }
    }

  private def applyEvent(event: Event): Unit =
    state = updateState(state, event)
      .getOrElse {
        val error = new IllegalStateException(s"Illegal state after applying [$event] to [$state]")
        log.error(error, error.getMessage)
        throw error
      }

  private def markSnapshotAsPendingIfNeeded(): Unit =
    snapshotPolicy match {
      case EachNumberOfEvents(numberOfEvents, _) if eventCount % numberOfEvents == 0 =>
        snapshotPending = true
      case _ => ()
    }

  private def snapshotIfPending(): Unit =
    snapshotPolicy match {
      case e @ EachNumberOfEvents(_, _) if snapshotPending =>
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
