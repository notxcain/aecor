package aecor.core.aggregate

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}

import aecor.core.aggregate.NowOrLater.{Deferred, Now}
import aecor.core.message._
import akka.NotUsed
import akka.actor.{ActorLogging, Props, Stash, Status}
import akka.cluster.sharding.ShardRegion
import akka.pattern._
import akka.persistence.journal.Tagged
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

sealed trait NowOrLater[+A] {
  def map[B](f: A => B): NowOrLater[B] = this match {
    case Now(value) => Now(f(value))
    case Deferred(run) => Deferred { implicit ec =>
      run(ec).map(f)
    }
  }
}

object NowOrLater {
  case class Now[+A](value: A) extends NowOrLater[A]
  case class Deferred[+A](run: ExecutionContext => Future[A]) extends NowOrLater[A]
}

case class CommandHandlerResult[Response, Event](response: Response, events: Seq[Event])

trait AggregateActorBehavior[A, State, Command, Response, Event] {
  def snapshot(a: A): State
  def applySnapshot(a: A)(state: State): A
  def handleCommand(a: A)(command: Command): NowOrLater[CommandHandlerResult[Response, Event]]
  def applyEvent(a: A)(event: Event): A
}

private [aecor] object AggregateActor {
  def props[Behavior, State, Command, Event, Response]
  (entityName: String,
    initialBehavior: Behavior,
    idleTimeout: FiniteDuration
  )(implicit Behavior: AggregateActorBehavior[Behavior, State, Command, Response, Event], Command: ClassTag[Command], State: ClassTag[State], Event: ClassTag[Event]): Props =
    Props(new AggregateActor(entityName, initialBehavior, idleTimeout))

  def extractEntityId[A: ClassTag](implicit correlation: Correlation[A]): ShardRegion.ExtractEntityId = {
    case a: A ⇒ (correlation(a), a)
  }
  def extractShardId[A: ClassTag](numberOfShards: Int)(implicit correlation: Correlation[A]): ShardRegion.ExtractShardId = {
    case a: A => ExtractShardId(correlation(a), numberOfShards)
  }
}

private [aecor] class AggregateActor[Behavior, State, Command, Event, Response]
(entityName: String,
 initialBehavior: Behavior,
 val idleTimeout: FiniteDuration
)(implicit Behavior: AggregateActorBehavior[Behavior, State, Command, Response, Event],
  Command: ClassTag[Command], State: ClassTag[State], Event: ClassTag[Event]) extends PersistentActor
  with Stash
  with ActorLogging
  with Passivation {

  import context.dispatcher

  private case class HandleCommandHandlerResult(result: CommandHandlerResult[Response, Event])

  private val entityId: String = URLDecoder.decode(self.path.name, StandardCharsets.UTF_8.name())
  override val persistenceId: String = s"$entityName-$entityId"

  var behavior = initialBehavior

  log.debug("[{}] Starting...", persistenceId)

  private val tags = Set(entityName)

  private val recoveryStartTimestamp: Instant = Instant.now()

  override def receiveRecover: Receive = {
    case e: Event =>
      applyEvent(e)

    case SnapshotOffer(metadata, snapshot: State) =>
      log.debug("Applying snapshot [{}]", snapshot)
      behavior = Behavior.applySnapshot(behavior)(snapshot)

    case RecoveryCompleted =>
      log.debug("[{}] Recovery completed in [{} ms]", persistenceId, Duration.between(recoveryStartTimestamp, Instant.now()).toMillis)
      setIdleTimeout()
  }

  override def receiveCommand: Receive = receivePassivationMessages.orElse(receiveCommandMessage)

  def receiveCommandMessage: Receive = {
    case command: Command =>
      handleCommand(command)
    case other =>
      log.warning("[{}] Unknown message [{}]", persistenceId, other)
  }

  def handleCommand(command: Command) = {
    log.debug("Received command [{}]", command)
    Behavior.handleCommand(behavior)(command) match {
      case Now(result) =>
        runResult(result)
      case Deferred(deferred) =>
        deferred(context.dispatcher).map(HandleCommandHandlerResult).pipeTo(self)(sender)
        context.become {
          case HandleCommandHandlerResult(result) =>
            runResult(result)
            unstashAll()
            context.become(receiveCommand)
          case failure @ Status.Failure(e) =>
            log.error(e, "Deferred result failed")
            sender() ! failure
            unstashAll()
            context.become(receiveCommand)
          case _ =>
            stash()
        }
    }
  }


  def runResult(result: CommandHandlerResult[Response, Event]): Unit = {
    log.debug("Command handler result [{}]", result)
    val envelopes = result.events.map(Tagged(_, tags))
    persistAll(envelopes) {
      case Tagged(e: Event, _) =>
        applyEvent(e)
    }
    deferAsync(NotUsed) { _ =>
      sender() ! result.response
    }
  }

  def applyEvent(event: Event): Unit = {
    log.debug("Applying event [{}]", event)
    behavior = Behavior.applyEvent(behavior)(event)
    log.debug("New behavior [{}]", behavior)
  }
}