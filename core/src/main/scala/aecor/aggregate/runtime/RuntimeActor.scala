package aecor.aggregate.runtime

import java.util.UUID

import aecor.effect.Async.ops._
import aecor.aggregate.runtime.RuntimeActor.PerformOp
import aecor.data.Behavior
import aecor.effect.Async
import akka.actor.{ Actor, ActorLogging, Props, ReceiveTimeout, Stash, Status }
import akka.cluster.sharding.ShardRegion
import akka.pattern.pipe
import cats.Functor

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

object RuntimeActor {
  def props[F[_]: Async: Functor, Op[_]](behavior: Behavior[Op, F],
                                         idleTimeout: FiniteDuration): Props =
    Props(new RuntimeActor(behavior, idleTimeout))

  final case class PerformOp[Op[_], A](op: Op[A])
  case object Stop
}

private[aecor] final class RuntimeActor[F[_]: Async, Op[_]](behavior: Behavior[Op, F],
                                                            idleTimeout: FiniteDuration)
    extends Actor
    with Stash
    with ActorLogging {

  import context._

  private case class Result(id: UUID, value: Try[(Behavior[Op, F], Any)])

  setIdleTimeout()

  override def receive: Receive = withBehavior(behavior)

  private def withBehavior(behavior: Behavior[Op, F]): Receive = {
    case PerformOp(op) =>
      val opId = UUID.randomUUID()
      behavior
        .run(op.asInstanceOf[Op[Any]])
        .unsafeRun
        .map(x => Result(opId, Success(x)))
        .recover {
          case NonFatal(e) => Result(opId, Failure(e))
        }
        .pipeTo(self)(sender)

      become {
        case Result(`opId`, value) =>
          value match {
            case Success((newBehavior, reply)) =>
              sender() ! reply
              become(withBehavior(newBehavior))
            case Failure(cause) =>
              sender() ! Status.Failure(cause)
              throw cause
          }
          unstashAll()
        case _ =>
          stash()
      }
    case ReceiveTimeout =>
      passivate()
    case RuntimeActor.Stop =>
      context.stop(self)
    case Result(_, _) =>
      log.debug(
        "Ignoring result of another operation. Probably targeted previous instance of actor."
      )
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
