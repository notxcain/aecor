package aecor.aggregate.runtime

import java.util.UUID

import aecor.aggregate.runtime.Async.ops._
import aecor.aggregate.runtime.RuntimeActor.PerformOp
import aecor.aggregate.runtime.behavior.Behavior
import akka.actor.{ Actor, ActorLogging, Props, ReceiveTimeout, Stash, Status }
import akka.cluster.sharding.ShardRegion
import akka.pattern.pipe
import cats.Eval.{ Call, Compute }
import cats.{ Always, Eval, Functor, Later, Now, ~> }
import cats.implicits._
import fs2.util.NonFatal

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.{ Failure, Success, Try }

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

  private final case class Result(id: UUID, value: Try[(Behavior[Op, F], Any)])

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
          case NonFatal(e) => Failure(e)
        }
        .pipeTo(self)(sender)

      become {
        case Result(`opId`, value) =>
          unstashAll()
          value match {
            case Success((newBehavior, reply)) =>
              sender() ! reply
              become(withBehavior(newBehavior))
            case Failure(cause) =>
              sender() ! Status.Failure(cause)
              throw cause
          }
        case Result(_, _) =>
          log.debug(
            "Ignoring result of another operation. Probably targeted previous instance of actor."
          )
        case ReceiveTimeout =>
          setIdleTimeout()
        case _ =>
          stash()
      }
    case ReceiveTimeout =>
      passivate()
      become {
        case RuntimeActor.Stop =>
          unstashAll()
          context.stop(self)
        case _ =>
          stash()
      }
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
