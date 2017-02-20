package aecor.aggregate.runtime

import aecor.aggregate.runtime.Async.ops._
import aecor.aggregate.runtime.RuntimeActor.PerformOp
import aecor.aggregate.runtime.behavior.Behavior
import akka.actor.{ Actor, ActorLogging, Props, ReceiveTimeout, Stash, Status }
import akka.cluster.sharding.ShardRegion
import akka.pattern.pipe
import cats.Functor
import cats.implicits._

import scala.concurrent.duration.FiniteDuration

object RuntimeActor {
  def props[Op[_], F[_]: Async: Functor](behavior: Behavior[Op, F],
                                         idleTimeout: FiniteDuration): Props =
    Props(new RuntimeActor(behavior, idleTimeout))

  final case class PerformOp[Op[_], A](op: Op[A])
  case object Stop
}

final class RuntimeActor[Op[_], F[_]: Async: Functor](behavior: Behavior[Op, F],
                                                      idleTimeout: FiniteDuration)
    extends Actor
    with Stash
    with ActorLogging {

  import context._

  private final case class Result(behavior: Behavior[Op, F], reply: Any)

  setIdleTimeout()

  override def receive: Receive = withBehavior(behavior)

  private def withBehavior(behavior: Behavior[Op, F]): Receive = {
    case PerformOp(op) =>
      behavior
        .run(op.asInstanceOf[Op[Any]])
        .map(x => Result(x._1, x._2))
        .unsafeRun
        .pipeTo(self)(sender)
      become {
        case Result(newBehavior, reply) =>
          sender() ! reply
          become(withBehavior(newBehavior))
          unstashAll()
        case failure @ Status.Failure(cause) =>
          sender() ! failure
          throw cause
        case _ =>
          stash()
      }
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
