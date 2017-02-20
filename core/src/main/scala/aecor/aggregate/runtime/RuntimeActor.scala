package aecor.aggregate.runtime

import java.util.UUID

import aecor.aggregate.runtime.Async.ops._
import aecor.aggregate.runtime.behavior.Behavior
import akka.actor.{ Actor, ActorLogging, Props, ReceiveTimeout, Stash, Status }
import akka.cluster.sharding.ShardRegion
import akka.pattern.pipe
import cats.Functor
import cats.implicits._

import scala.concurrent.duration.FiniteDuration

object RuntimeActor {
  def props[F[_]: Async: Functor, Op[_]](behaviorForInstanceId: UUID => Behavior[Op, F],
                                         idleTimeout: FiniteDuration): Props =
    Props(new RuntimeActor(behaviorForInstanceId, idleTimeout))

  case object Stop
}

final class RuntimeActor[Op[_], F[_]: Async: Functor](
  behaviorForInstanceId: UUID => Behavior[Op, F],
  idleTimeout: FiniteDuration
) extends Actor
    with Stash
    with ActorLogging {

  import context._

  private final case class Result(behavior: Behavior[Op, F], reply: Any)

  private val instanceId = UUID.randomUUID

  setIdleTimeout()

  override def receive: Receive = active(behaviorForInstanceId(instanceId))

  private def active(behavior: Behavior[Op, F]): Receive = receivePassivationMessages.orElse {
    case op =>
      behavior
        .run(op.asInstanceOf[Op[Any]])
        .map(x => Result(x._1, x._2))
        .run
        .pipeTo(self)(sender)
      become {
        case Result(newBehavior, reply) =>
          sender() ! reply
          become(active(newBehavior))
          unstashAll()
        case failure @ Status.Failure(cause) =>
          sender() ! failure
          throw cause
        case _ =>
          stash()
      }
  }

  private def receivePassivationMessages: Receive = {
    case ReceiveTimeout =>
      passivate()
    case RuntimeActor.Stop =>
      context.stop(self)
  }

  private def passivate(): Unit = {
    log.debug("Passivating...")
    context.parent ! ShardRegion.Passivate(RuntimeActor.Stop)
  }

  private def setIdleTimeout(): Unit = {
    log.debug("Setting idle timeout to [{}]", idleTimeout)
    context.setReceiveTimeout(idleTimeout)
  }
}
