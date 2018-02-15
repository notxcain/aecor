package aecor.runtime.akkageneric

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

import aecor.data.{ Behavior, PairT }
import aecor.util.effect._
import aecor.encoding.KeyDecoder
import aecor.runtime.akkageneric.GenericAkkaRuntimeActor.PerformOp
import akka.actor.{ Actor, ActorLogging, Props, ReceiveTimeout, Stash, Status }
import akka.cluster.sharding.ShardRegion
import akka.pattern.pipe
import cats.effect.Effect
import io.aecor.liberator.Algebra

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

object GenericAkkaRuntimeActor {
  def props[I: KeyDecoder, M[_[_]]: Algebra, F[_]: Effect](createBehavior: I => Behavior[M, F],
                                                           idleTimeout: FiniteDuration): Props =
    Props(new GenericAkkaRuntimeActor(createBehavior, idleTimeout))

  private[akkageneric] final case class PerformOp[I, Op[_], A](op: Op[A])
  private[akkageneric] case object Stop
}

private[aecor] final class GenericAkkaRuntimeActor[I: KeyDecoder, M[_[_]], F[_]: Effect](
  createBehavior: I => Behavior[M, F],
  idleTimeout: FiniteDuration
)(implicit M: Algebra[M])
    extends Actor
    with Stash
    with ActorLogging {

  import context._

  private val idString: String =
    URLDecoder.decode(self.path.name, StandardCharsets.UTF_8.name())

  private val id: I = KeyDecoder[I]
    .decode(idString)
    .getOrElse {
      val error = s"Failed to decode entity id from [$idString]"
      log.error(error)
      throw new IllegalArgumentException(error)
    }

  private case class Result(id: UUID, value: Try[(Behavior[M, F], Any)])

  setIdleTimeout()

  override def receive: Receive = withBehavior(createBehavior(id))

  private def withBehavior(behavior: Behavior[M, F]): Receive = {
    val actionsK = M.toFunctionK[PairT[F, Behavior[M, F], ?]](behavior.actions)

    {
      case PerformOp(op) =>
        val opId = UUID.randomUUID()

        (actionsK(op.asInstanceOf[M.Out[Any]]): F[(Behavior[M, F], Any)]).toIO // Stupid type hint to make IDEA happy
          .map(x => Result(opId, Success(x)))
          .unsafeToFuture()
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
        log.warning(
          "Ignoring result of another operation. Probably targeted previous instance of actor."
        )
    }
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
