package aecor.runtime.queue

import java.nio.ByteBuffer
import java.util.UUID

import aecor.data.PairE
import aecor.encoding.WireProtocol
import aecor.runtime.queue.Actor.Receive
import aecor.runtime.queue.Runtime._
import aecor.runtime.queue.impl.ConcurrentHashMapDeferredRegistry
import _root_.io.aecor.liberator.ReifiedInvocations
import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import cats.{ Applicative, ~> }
import io.aecor.liberator.Invocation
import fs2._
import org.http4s.booPickle._
import org.http4s.{ EntityDecoder, EntityEncoder }
import scodec.bits.BitVector
import scodec.Encoder
import aecor.encoding.syntax._

import scala.concurrent.duration.FiniteDuration

class Runtime[F[_]] private[queue] (
  idleTimeout: FiniteDuration,
  registry: DeferredRegistry[F, CommandId, BitVector]
)(implicit
  F: ConcurrentEffect[F],
  timer: Timer[F]) {

  def run[K, I, M[_[_]]](
    create: K => F[M[F]],
    clientServer: ClientServer[F, I, CommandResult],
    commandQueue: PartitionedQueue[F, CommandEnvelope[I, K]]
  )(implicit M: WireProtocol[M], MI: ReifiedInvocations[M]): Resource[F, K => M[F]] = {

    def startCommandProcessor(selfMemberId: I,
                              create: K => F[M[F]],
                              commandPartitions: Stream[F, Stream[F, CommandEnvelope[I, K]]],
                              sendResponse: (I, CommandResult) => F[Unit]): Resource[F, Unit] =
      Resource[F, Unit] {
        commandPartitions
          .mapAsyncUnordered(Int.MaxValue) { commands =>
            val startShard =
              ActorShard.create(idleTimeout) { key: K =>
                Actor.create[F, (CommandId, I, PairE[Invocation[M, ?], Encoder])] { _ =>
                  create(key).map { mf =>
                    Receive[(CommandId, I, PairE[Invocation[M, ?], Encoder])] {
                      case (commandId, replyTo, pair) =>
                        val (inv, enc) = (pair.first, pair.second)
                        inv
                          .invoke(mf)
                          .map(enc.encode)
                          .flatMap(_.lift[F])
                          .flatMap { bytes =>
                            if (replyTo == selfMemberId) {
                              registry.fulfill(commandId, bytes)
                            } else {
                              sendResponse(replyTo, CommandResult(commandId, bytes))
                            }
                          }
                    }
                  }
                }
              }
            Stream
              .bracket(startShard)(_.terminateAndWatch)
              .flatMap { shard =>
                commands.evalMap {
                  case CommandEnvelope(commandId, replyTo, key, bytes) =>
                    M.decoder.decodeValue(bytes).lift[F].flatMap { pair =>
                      shard.send((key, (commandId, replyTo, pair)))
                    }
                }
              }
              .drain
              .compile
              .drain
          }
          .compile
          .drain
          .start
          .map(f => ((), f.cancel))
      }

    for {
      ClientServer
        .Instance(selfId, responseSender) <- clientServer.start(
                                              body => registry.fulfill(body.commandId, body.bytes)
                                            )
      PartitionedQueue.Instance(enqueue, commandPartitions) <- commandQueue.start
      _ <- startCommandProcessor(selfId, create, commandPartitions, responseSender)
      encoder = M.encoder
    } yield { key: K =>
      MI.mapInvocations(new (Invocation[M, ?] ~> F) {
        override def apply[A](invocation: Invocation[M, A]): F[A] = {
          val (bytes, decoder) = invocation.invoke(encoder)
          for {
            commandId <- F.delay(CommandId(UUID.randomUUID()))
            waitForResult <- registry.defer(commandId)
            envelope = CommandEnvelope(commandId, selfId, key, bytes)
            _ <- enqueue(envelope)
            responseBytes <- waitForResult
            out <- decoder.decodeValue(responseBytes).lift[F]
          } yield out
        }
      })
    }
  }

}

object Runtime {
  final case class CommandResult(commandId: CommandId, bytes: BitVector)
  object CommandResult {
    import boopickle.Default._
    implicit val pickler = transformPickler((b: ByteBuffer) => BitVector(b))(_.toByteBuffer)
    implicit def entityDecoder[F[_]: Sync]: EntityDecoder[F, CommandResult] =
      booOf[F, CommandResult]
    implicit def entityEncoder[F[_]: Applicative]: EntityEncoder[F, CommandResult] =
      booEncoderOf[F, CommandResult]
  }
  final case class CommandId(value: UUID) extends AnyVal
  final case class CommandEnvelope[I, K](commandId: CommandId, replyTo: I, key: K, bytes: BitVector)
  private[queue] final case class ResponseEnvelope[K](commandId: CommandId, bytes: BitVector)

  def create[F[_]: ConcurrentEffect: ContextShift: Timer](
    requestTimeout: FiniteDuration,
    idleTimeout: FiniteDuration
  ): F[Runtime[F]] =
    for {
      registry <- ConcurrentHashMapDeferredRegistry.create[F, CommandId, BitVector](requestTimeout)
    } yield new Runtime(idleTimeout, registry)
}
