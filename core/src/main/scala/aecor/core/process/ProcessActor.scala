package aecor.core.process

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import aecor.core.aggregate._
import aecor.core.message._
import aecor.core.process.ProcessActor.ProcessBehavior
import aecor.core.process.ProcessEvent.{CommandAccepted, CommandRejected, EventEnvelope}
import aecor.core.process.ProcessReaction.{CompoundReaction, _}
import akka.actor.{ActorLogging, Props}
import akka.cluster.sharding.ShardRegion
import akka.persistence.{AtLeastOnceDelivery, PersistentActor, RecoveryCompleted}

import scala.concurrent.duration._
import scala.reflect.ClassTag

sealed trait ProcessReaction[+In] {
  def and[In0 >: In](that: ProcessReaction[In0]): ProcessReaction[In0] = {
    CompoundReaction(this, that)
  }
}
object ProcessReaction {
  case class ChangeBehavior[In](f: In => ProcessReaction[In]) extends ProcessReaction[In]
  case class DeliverCommand[In, Entity, Rejection, Command](destination: AggregateRef[Entity], command: Command, rejectionHandler: Rejection => ProcessReaction[In], commandId: CommandId) extends ProcessReaction[In]
  case class CompoundReaction[In](l: ProcessReaction[In], r: ProcessReaction[In]) extends ProcessReaction[In]
  case object DoNothing extends ProcessReaction[Nothing]
}

case class HandleEvent[+A](id: EventId, event: A) {
  def map[B](f: A => B): HandleEvent[B] = copy(event = f(event))
}
case class EventHandled(causedBy: EventId)

object ProcessActor {
  type ProcessBehavior[E] = E => ProcessReaction[E]

  def extractEntityId[A: ClassTag](implicit correlation: Correlation[A]): ShardRegion.ExtractEntityId = {
    case m @ HandleEvent(_, a: A) ⇒ (correlation(a), m)
  }
  def extractShardId[A: ClassTag](numberOfShards: Int)(implicit correlation: Correlation[A]): ShardRegion.ExtractShardId = {
    case m @ HandleEvent(_, a: A) => ExtractShardId(correlation(a), numberOfShards)
  }

  def props[Input: ClassTag](processName: String, initialBehavior: ProcessBehavior[Input], idleTimeout: FiniteDuration): Props =
    Props(new ProcessActor[Input](processName, initialBehavior, idleTimeout))
}


private [aecor] case class ProcessActorState[Input](behavior: ProcessBehavior[Input], processedEvents: Set[EventId]) {
  def shouldProcessEvent(commandId: EventId): Boolean = processedEvents(commandId)
}


class ProcessActor[Input: ClassTag](processName: String, initialBehavior: ProcessBehavior[Input], val idleTimeout: FiniteDuration)
  extends PersistentActor
    with AtLeastOnceDelivery
    with Deduplication[EventId]
    with Passivation
    with ActorLogging {

  private val processId: String = URLDecoder.decode(self.path.name, StandardCharsets.UTF_8.name())

  override def persistenceId: String = processName + "-" + processId

  private var behavior = initialBehavior

  override def receiveRecover: Receive =  {
    case EventEnvelope(id, event: Input) =>
      val reaction = behavior(event)
      runReaction(reaction)
      confirmProcessed(id)
    case CommandRejected(rejection, deliveryId) =>
      commandRejected(rejection, deliveryId)
    case CommandAccepted(deliveryId) =>
      confirmCommandDelivery(deliveryId)
      ()
    case RecoveryCompleted =>
      setIdleTimeout()
  }

  override def receiveCommand: Receive = receivePassivationMessages.orElse {
    case HandleEvent(id, event: Input) =>
      if (isProcessed(id)) {
        sender() ! EventHandled(id)
        log.debug("Received duplicate event [{}]", event)
      } else {
        persist(EventEnvelope(id, event)) { em =>
          val reaction = behavior(event)
          log.debug("Event [{}] caused reaction [{}]", event, reaction)
          runReaction(reaction)
          confirmProcessed(id)
          sender() ! EventHandled(id)
        }
      }

    case AggregateResponse(causedBy, result) => result match {
      case Rejected(rejection) =>
        log.debug("Command [{}] rejected [{}]", causedBy, rejection)
        persist(CommandRejected(rejection, causedBy)) { _ =>
          commandRejected(rejection, causedBy)
        }
      case Accepted =>
        log.debug("Command [{}] accepted", causedBy)
        persist(CommandAccepted(causedBy)) { e =>
          confirmCommandDelivery(causedBy)
        }
    }
  }

  private def runReaction(reaction: ProcessReaction[Input]): Unit = reaction match {
    case ChangeBehavior(newBehavior) =>
      behavior = newBehavior
    case DeliverCommand(destination, command, rejectionHandler, commandId) =>
      deliverCommand(destination, command, rejectionHandler, commandId)
    case CompoundReaction(l, r) =>
      runReaction(l)
      runReaction(r)
    case DoNothing =>
      ()
  }

  type RejectionHandler = Any => ProcessReaction[Input]
  type InternalDeliveryId = Long

  case class CommandDelivery(rejectionHandler: RejectionHandler, internalDeliveryId: InternalDeliveryId)

  val activeDeliveries = scala.collection.mutable.Map.empty[CommandId, CommandDelivery]

  def deliverCommand[A, C, R](destination: AggregateRef[A], command: C, rejectionHandler: R => ProcessReaction[Input], commandId: CommandId): Unit = {
    deliver(destination.actorRef.path) { internalDeliveryId =>
      activeDeliveries.update(commandId, CommandDelivery(rejectionHandler.asInstanceOf[RejectionHandler], internalDeliveryId))
      HandleCommand(commandId, command)
    }
  }

  def commandRejected(rejection: Any, commandId: CommandId): Unit = {
    handleRejection(rejection, commandId)
    confirmCommandDelivery(commandId)
  }

  def handleRejection(rejection: Any, commandId: CommandId): Unit = {
    activeDeliveries.get(commandId).foreach { delivery =>
      val reaction = delivery.rejectionHandler(rejection)
      log.debug("Handling rejection [{}] of command [{}] with reaction [{}]", rejection, commandId, reaction)
      runReaction(reaction)
    }
  }

  def confirmCommandDelivery(commandId: CommandId): Unit = {
    activeDeliveries.get(commandId).foreach { delivery =>
      activeDeliveries - commandId
      confirmDelivery(delivery.internalDeliveryId)
    }
  }

  override def shouldPassivate: Boolean = numberOfUnconfirmed == 0
}
