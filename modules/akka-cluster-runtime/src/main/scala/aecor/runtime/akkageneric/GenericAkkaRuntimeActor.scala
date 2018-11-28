package aecor.runtime.akkageneric

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

import aecor.encoding.WireProtocol.Invocation
import aecor.encoding.{ KeyDecoder, WireProtocol }
import aecor.runtime.akkageneric.GenericAkkaRuntimeActor.{ Command, CommandResult }
import aecor.runtime.akkageneric.serialization.Message
import akka.actor.{ Actor, ActorLogging, Props, ReceiveTimeout, Stash, Status }
import akka.cluster.sharding.ShardRegion
import akka.pattern.pipe
import cats.effect.{ Effect, IO }
import cats.effect.syntax.effect._
import scodec.{ Attempt, Encoder }
import scodec.bits.BitVector
import aecor.encoding.syntax._

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }
import cats.syntax.apply._

private[aecor] object GenericAkkaRuntimeActor {
  def props[K: KeyDecoder, M[_[_]]: WireProtocol, F[_]: Effect](
    createBehavior: K => F[M[F]],
    idleTimeout: FiniteDuration
  ): Props =
    Props(new GenericAkkaRuntimeActor[K, M, F](createBehavior, idleTimeout))

  private[akkageneric] final case class Command(bytes: BitVector) extends Message
  private[akkageneric] final case class CommandResult(bytes: BitVector) extends Message
  private[akkageneric] case object Stop
}

private[aecor] final class GenericAkkaRuntimeActor[K: KeyDecoder, M[_[_]], F[_]: Effect](
  createBehavior: K => F[M[F]],
  idleTimeout: FiniteDuration
)(implicit M: WireProtocol[M])
    extends Actor
    with Stash
    with ActorLogging {

  import context._

  private val keyString: String =
    URLDecoder.decode(self.path.name, StandardCharsets.UTF_8.name())

  private val key: K = KeyDecoder[K]
    .decode(keyString)
    .getOrElse {
      val error = s"Failed to decode entity id from [$keyString]"
      log.error(error)
      throw new IllegalArgumentException(error)
    }

  private case class Result(id: UUID, value: Try[BitVector])
  private case class Actions(value: M[F])

  setIdleTimeout()

  createBehavior(key).toIO.map(Actions).unsafeToFuture() pipeTo self

  override def receive: Receive = {
    case Actions(actions) =>
      unstashAll()
      become(withActions(actions))
    case _ => stash()
  }

  private def withActions(actions: M[F]): Receive = {
    case Command(opBytes) =>
      M.decoder
        .decodeValue(opBytes) match {
        case Attempt.Successful(pair) =>
          log.debug("[{}] [{}] Received invocation [{}]", self.path, key, pair.first.toString)
          performInvocation(actions, pair.first, pair.second)
        case Attempt.Failure(cause) =>
          val decodingError = new IllegalArgumentException(cause.messageWithContext)
          log.error(decodingError, "Failed to decode invocation")
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

  def performInvocation[A](actions: M[F],
                           invocation: Invocation[M, A],
                           resultEncoder: Encoder[A]): Unit = {
    val opId = UUID.randomUUID()
    invocation
      .run(actions)
      .toIO
      .flatMap(a =>
        IO(log.debug("[{}] [{}] Invocation result [{}]", self.path, key, a.toString)) *>
          resultEncoder.encode(a).lift[IO]
      )
      .map { responseBytes =>
        Result(opId, Success(responseBytes))
      }
      .unsafeToFuture()
      .recover {
        case NonFatal(e) => Result(opId, Failure(e))
      }
      .pipeTo(self)(sender)

    become {
      case Result(`opId`, value) =>
        value match {
          case Success(reply) =>
            sender() ! CommandResult(reply)
            become(withActions(actions))
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
