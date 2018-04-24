package aecor.runtime.akkageneric

import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

import io.aecor.liberator.Invocation
import aecor.data.{ Behavior, PairT }
import aecor.encoding.KeyDecoder
import aecor.encoding.WireProtocol
import aecor.runtime.akkageneric.GenericAkkaRuntimeActor.{ Command, CommandResult }
import aecor.runtime.akkageneric.serialization.Message
import aecor.util.effect._
import akka.actor.{ Actor, ActorLogging, Props, ReceiveTimeout, Stash, Status }
import akka.cluster.sharding.ShardRegion
import akka.pattern.pipe
import cats.effect.Effect

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

private[aecor] object GenericAkkaRuntimeActor {
  def props[I: KeyDecoder, M[_[_]]: WireProtocol, F[_]: Effect](
    createBehavior: I => Behavior[M, F],
    idleTimeout: FiniteDuration
  ): Props =
    Props(new GenericAkkaRuntimeActor(createBehavior, idleTimeout))

  private[akkageneric] final case class Command(bytes: ByteBuffer) extends Message
  private[akkageneric] final case class CommandResult(bytes: ByteBuffer) extends Message
  private[akkageneric] case object Stop
}

private[aecor] final class GenericAkkaRuntimeActor[I: KeyDecoder, M[_[_]], F[_]: Effect](
  createBehavior: I => Behavior[M, F],
  idleTimeout: FiniteDuration
)(implicit M: WireProtocol[M])
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

  private case class Result(id: UUID, value: Try[(Behavior[M, F], ByteBuffer)])

  setIdleTimeout()

  override def receive: Receive = withBehavior(createBehavior(id))

  private def withBehavior(behavior: Behavior[M, F]): Receive = {
    case Command(opBytes) =>
      M.decoder
        .decode(opBytes) match {
        case Right(pair) =>
          performInvocation(behavior.actions, pair.left, pair.right)
        case Left(decodingError) =>
          val error = s"Failed to decode invocation, because of $decodingError"
          log.error(error)
          sender() ! Status.Failure(decodingError)
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

  def performInvocation[A](actions: M[PairT[F, Behavior[M, F], ?]],
                           invocation: Invocation[M, A],
                           resultEncoder: WireProtocol.Encoder[A]): Unit = {
    val opId = UUID.randomUUID()
    val invocationResult: F[(Behavior[M, F], A)] = // Stupid type hint to make IDEA happy
      invocation.invoke[PairT[F, Behavior[M, F], ?]](actions) // Stupid type hint to make scalac happy

    invocationResult.toIO
      .map {
        case (nextBehavior, response) =>
          Result(opId, Success((nextBehavior, resultEncoder.encode(response))))
      }
      .unsafeToFuture()
      .recover {
        case NonFatal(e) => Result(opId, Failure(e))
      }
      .pipeTo(self)(sender)

    become {
      case Result(`opId`, value) =>
        value match {
          case Success((newBehavior, reply)) =>
            sender() ! CommandResult(reply)
            become(withBehavior(newBehavior))
          case Failure(cause) =>
            sender() ! Status.Failure(cause)
            throw cause
        }
        unstashAll()
      case _ =>
        stash()
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
