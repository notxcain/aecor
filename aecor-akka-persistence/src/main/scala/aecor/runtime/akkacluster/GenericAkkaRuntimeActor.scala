package aecor.runtime.akkacluster

import java.util.UUID

import aecor.data.Behavior
import aecor.effect.Async
import aecor.effect.Async.ops._
import aecor.runtime.akkacluster.GenericAkkaRuntimeActor.PerformOp
import akka.actor.{ Actor, ActorLogging, Props, ReceiveTimeout, Stash, Status }
import akka.cluster.sharding.ShardRegion
import akka.pattern.pipe
import cats.Functor

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

object GenericAkkaRuntimeActor {
  def props[F[_]: Async: Functor, Op[_]](behavior: Behavior[F, Op],
                                         idleTimeout: FiniteDuration): Props =
    Props(new GenericAkkaRuntimeActor(behavior, idleTimeout))

  final case class PerformOp[Op[_], A](op: Op[A])
  case object Stop
}

private[aecor] final class GenericAkkaRuntimeActor[F[_]: Async, Op[_]](behavior: Behavior[F, Op],
                                                                       idleTimeout: FiniteDuration)
    extends Actor
    with Stash
    with ActorLogging {

  import context._

  private case class Result(id: UUID, value: Try[(Behavior[F, Op], Any)])

  setIdleTimeout()

  override def receive: Receive = withBehavior(behavior)

  private def withBehavior(behavior: Behavior[F, Op]): Receive = {
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
    case GenericAkkaRuntimeActor.Stop =>
      context.stop(self)
    case Result(_, _) =>
      log.debug(
        "Ignoring result of another operation. Probably targeted previous instance of actor."
      )
  }

  private def passivate(): Unit = {
    log.debug("Passivating...")
    context.parent ! ShardRegion.Passivate(GenericAkkaRuntimeActor.Stop)
  }

  private def setIdleTimeout(): Unit = {
    log.debug("Setting idle timeout to [{}]", idleTimeout)
    context.setReceiveTimeout(idleTimeout)
  }
}
