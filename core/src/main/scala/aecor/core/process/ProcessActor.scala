package aecor.core.process

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import aecor.core.entity._
import aecor.core.logging.PersistentActorLogging
import aecor.core.message._
import aecor.core.process.ProcessActor.{CommandDelivered, ProcessBehavior}
import aecor.core.process.ProcessEvent.{CommandAccepted, CommandRejected, EventEnvelope}
import aecor.core.process.ProcessReaction.{CompoundReaction, _}
import akka.actor.Props
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
  case class DeliverCommand[In, Entity, Rejection, Command](destination: EntityRef[Entity], command: Command, rejectionHandler: Rejection => ProcessReaction[In]) extends ProcessReaction[In]
  case class CompoundReaction[In](l: ProcessReaction[In], r: ProcessReaction[In]) extends ProcessReaction[In]
  case object DoNothing extends ProcessReaction[Nothing]
}

object ProcessActor {
  type ProcessBehavior[E] = E => ProcessReaction[E]

  case class CommandDelivered(deliveryId: Long)

  def props[Input: ClassTag](processName: String, initialBehavior: ProcessBehavior[Input], idleTimeout: FiniteDuration): Props =
    Props(new ProcessActor[Input](processName, initialBehavior, idleTimeout))
}

class ProcessActor[Input: ClassTag](processName: String, initialBehavior: ProcessBehavior[Input], val idleTimeout: FiniteDuration)
  extends PersistentActor
    with AtLeastOnceDelivery
    with Deduplication
    with Passivation
    with PersistentActorLogging {

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
      confirmDelivery(deliveryId)
      ()
    case RecoveryCompleted =>
      setIdleTimeout()
  }

  override def receiveCommand: Receive = receivePassivationMessages.orElse {
    case Message(id, event: Input, deliveryAck) =>
      if (isProcessed(id)) {
        sender() ! deliveryAck
        log.debug("Received duplicate event [{}]", event)
      } else {
        persist(EventEnvelope(id, event)) { em =>
          val reaction = behavior(event)
          log.debug("Processed event [{}] with reaction [{}]", event, reaction)
          runReaction(reaction)
          confirmProcessed(id)
          sender() ! deliveryAck
        }
      }

    case EntityResponse(CommandDelivered(deliveryId), result) => result match {
      case Rejected(rejection) =>
        log.debug("Command [{}] rejected [{}]", deliveryId, rejection)
        persist(CommandRejected(rejection, deliveryId)) { _ =>
          commandRejected(rejection, deliveryId)
        }
      case Accepted =>
        log.debug("Command [{}] accepted", deliveryId)
        persist(CommandAccepted(deliveryId)) { e =>
          confirmDelivery(deliveryId)
          ()
        }
    }
  }

  private def runReaction(reaction: ProcessReaction[Input]): Unit = reaction match {
    case ChangeBehavior(newBehavior) =>
      behavior = newBehavior
    case DeliverCommand(destination, command, rejectionHandler) =>
      deliverCommand(destination, command, rejectionHandler)
    case CompoundReaction(l, r) =>
      runReaction(l)
      runReaction(r)
    case DoNothing =>
      ()
  }

  type RejectionHandler = Any => ProcessReaction[Input]

  val rejectionHandlers = scala.collection.mutable.Map.empty[Long, RejectionHandler]

  def deliverCommand[A, C, R](destination: EntityRef[A], command: C, rejectionHandler: R => ProcessReaction[Input]): Unit = {
    deliver(destination.actorRef.path) { deliveryId =>
      rejectionHandlers.update(deliveryId, rejection => rejectionHandler(rejection.asInstanceOf[R]))
      Message(MessageId.generate, command, CommandDelivered(deliveryId))
    }
  }

  def commandRejected(rejection: Any, deliveryId: Long): Unit = {
    rejectionHandlers.get(deliveryId).map { handler =>
      handler(rejection)
    }.foreach { reaction =>
      log.debug("Handling rejection [{}] of command [{}] with reaction [{}]", rejection, deliveryId, reaction)
      runReaction(reaction)
    }
    confirmDelivery(deliveryId)
    ()
  }

  override def confirmDelivery(deliveryId: Long): Boolean = {
    rejectionHandlers - deliveryId
    super.confirmDelivery(deliveryId)
  }

  override def shouldPassivate: Boolean = numberOfUnconfirmed == 0
}
