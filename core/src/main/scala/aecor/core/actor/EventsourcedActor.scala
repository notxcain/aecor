package aecor.core.actor

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}

import aecor.core.message._
import akka.NotUsed
import akka.actor.{ActorLogging, Props, Stash, Status}
import akka.cluster.sharding.ShardRegion
import akka.persistence.journal.Tagged
import akka.persistence.{PersistentActor, RecoveryCompleted}

import scala.collection.immutable.Seq
import scala.concurrent._
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import akka.pattern.pipe
import NowOrDeferred._

sealed trait NowOrDeferred[+A] {
  def map[B](f: A => B): NowOrDeferred[B] = this match {
    case Now(value) => Now(f(value))
    case Deferred(run) => Deferred { implicit ec =>
      run(ec).map(f)
    }
  }
  def flatMap[B](f: A => NowOrDeferred[B]): NowOrDeferred[B] = this match {
    case Now(value) => f(value)
    case Deferred(run) => Deferred { implicit ec =>
      run(ec).flatMap { a =>
        f(a) match {
          case Now(value) => Future.successful(value)
          case Deferred(x) => x(ec)
        }
      }
    }
  }
}

object NowOrDeferred {

  case class Now[+A](value: A) extends NowOrDeferred[A]

  case class Deferred[+A](run: ExecutionContext => Future[A]) extends NowOrDeferred[A]

}

trait EventsourcedBehavior[A] {
  type Command
  type Response
  type Event

  def handleCommand(a: A)(command: Command): NowOrDeferred[EventsourcedBehavior.Result[Response, Event]]

  def applyEvent(a: A)(event: Event): A
}

object EventsourcedBehavior {
  type Aux[A, Command0, Response0, Event0] = EventsourcedBehavior[A] {
    type Command = Command0
    type Response = Response0
    type Event = Event0
  }
  type Result[Response, Event] = (Response, Seq[Event])
  def apply[A](implicit A: EventsourcedBehavior[A]): EventsourcedBehavior.Aux[A, A.Command, A.Response, A.Event] = A
}

private[aecor] object EventsourcedActor {
  trait MkProps[Behavior] {
    def apply[Command, Event, Response]
    (entityName: String,
     idleTimeout: FiniteDuration
    )(implicit
      actorBehavior: EventsourcedBehavior.Aux[Behavior, Command, Response, Event],
      Command: ClassTag[Command],
      Event: ClassTag[Event]
    ): Props
  }
  def props[Behavior](initialBehavior: Behavior) = new MkProps[Behavior] {
    override def apply[Command, Event, Response]
    (entityName: String, idleTimeout: FiniteDuration)
    (implicit actorBehavior: EventsourcedBehavior.Aux[Behavior, Command, Response, Event], Command: ClassTag[Command], Event: ClassTag[Event]): Props =
      Props(new EventsourcedActor[Behavior, Command, Event, Response](entityName, initialBehavior, idleTimeout))
  }

  def extractEntityId[A: ClassTag](correlation: A => String): ShardRegion.ExtractEntityId = {
    case a: A ⇒ (correlation(a), a)
  }

  def extractShardId[A: ClassTag](numberOfShards: Int)(correlation: A => String): ShardRegion.ExtractShardId = {
    case a: A => ExtractShardId(correlation(a), numberOfShards)
  }
}

private[aecor] class EventsourcedActor[Behavior, Command, Event, Response]
(entityName: String,
 initialBehavior: Behavior,
 val idleTimeout: FiniteDuration
)(implicit
  actorBehavior: EventsourcedBehavior.Aux[Behavior, Command, Response, Event],
  Command: ClassTag[Command],
  Event: ClassTag[Event]
) extends PersistentActor
          with Stash
          with ActorLogging
          with Passivation {

  import context.dispatcher

  private case class HandleResult(result: EventsourcedBehavior.Result[Response, Event])

  protected val entityId: String = URLDecoder.decode(self.path.name, StandardCharsets.UTF_8.name())
  override val persistenceId: String = s"$entityName-$entityId"

  protected var behavior: Behavior = initialBehavior

  log.debug("[{}] Starting...", persistenceId)

  private val tags = Set(entityName)

  private val recoveryStartTimestamp: Instant = Instant.now()

  override def receiveRecover: Receive = {
    case e: Event =>
      applyEvent(e)

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
    log.debug("[{}] Received command [{}]", persistenceId, command)
    actorBehavior.handleCommand(behavior)(command) match {
      case Now(result) =>
        runResult(result)
      case Deferred(deferred) =>
        deferred(context.dispatcher).map(HandleResult).pipeTo(self)(sender)
        context.become {
          case HandleResult(result) =>
            runResult(result)
            unstashAll()
            context.become(receiveCommand)
          case failure@Status.Failure(e) =>
            log.error(e, "[{}] Deferred result failed", persistenceId)
            sender() ! failure
            unstashAll()
            context.become(receiveCommand)
          case _ =>
            stash()
        }
    }
  }


  def runResult(result: EventsourcedBehavior.Result[Response, Event]): Unit = {
    val (response, events) = result
    log.debug("[{}] Command handler result [{}]", persistenceId, result)
    val envelopes = events.map(Tagged(_, tags))
    persistAll(envelopes) {
      case Tagged(e: Event, _) =>
        applyEvent(e)
    }
    deferAsync(NotUsed) { _ =>
      sender() ! response
    }
  }

  def applyEvent(event: Event): Unit = {
    behavior = actorBehavior.applyEvent(behavior)(event)
    log.debug("[{}] New behavior [{}]", persistenceId, behavior)
  }
}