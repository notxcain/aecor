package aecor.runtime.queue

import java.nio.ByteBuffer
import java.util.UUID

import aecor.data.PairE
import aecor.encoding.WireProtocol
import aecor.encoding.WireProtocol.Encoder
import aecor.runtime.queue.Actor.Receive
import aecor.runtime.queue.DeferredRegistry.StoreItem
import aecor.runtime.queue.Runtime._
import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import cats.{Applicative, ~>}
import io.aecor.liberator.Invocation
import fs2._
import org.http4s.booPickle._
import org.http4s.dsl.Http4sDsl
import org.http4s.{EntityDecoder, EntityEncoder}

import scala.concurrent.duration.FiniteDuration

class Runtime[F[_]] private[queue] (
                                    idleTimeout: FiniteDuration,
                                    registry: DeferredRegistry[F, CommandId, Array[Byte]]
)(implicit
  F: ConcurrentEffect[F],
  timer: Timer[F])
    extends Http4sDsl[F] {

  def run[K, I, M[_[_]]](
    create: K => F[M[F]],
    clientServer: ClientServer[F, I, CommandResponse],
    commandQueue: PartitionedQueue[F, CommandEnvelope[I, K]]
  )(implicit M: WireProtocol[M]): Resource[F, K => M[F]] = {

    def startCommandProcessor(
      create: K => F[M[F]],
      commandPartitions: Stream[F, Stream[F, CommandEnvelope[I, K]]],
      sendResponse: (I, CommandResponse) => F[Unit]
    ): Resource[F, Unit] =
      Resource[F, Unit] {
        commandPartitions
          .map { commands =>
            val startShard =
              ActorShard.create[F, K, (CommandId, I, PairE[Invocation[M, ?], Encoder])](
                idleTimeout
              ) { key =>
                Actor.create[F, (CommandId, I, PairE[Invocation[M, ?], Encoder])] { _ =>
                  create(key).map { mf =>
                    Receive[(CommandId, I, PairE[Invocation[M, ?], Encoder])] {
                      case (commandId, replyTo, pair) =>
                        val (inv, enc) = (pair.first, pair.second)
                        inv.invoke(mf).map(enc.encode).flatMap { bytes =>
                          sendResponse(replyTo, CommandResponse(commandId, bytes.array()))
                        }
                    }
                  }
                }
              }
            Stream.bracket(startShard)(_.terminate).flatMap { shard =>
              commands.evalMap {
                case CommandEnvelope(commandId, replyTo, key, bytes) =>
                  F.fromEither(M.decoder.decode(ByteBuffer.wrap(bytes))).flatMap { pair =>
                    shard.send((key, (commandId, replyTo, pair)))
                  }
              }
            }
          }
          .parJoinUnbounded
          .compile
          .drain
          .start
          .map(f => ((), f.cancel))
      }

    for {
      (selfAddress, responseSender) <- clientServer.start(body => registry.fulfill(body.commandId, body.bytes))
      (commands, commandPartitions) <- commandQueue.start
      _ <- startCommandProcessor(create, commandPartitions, responseSender)
      encoder = M.encoder
    } yield { key: K =>
      M.mapInvocations(new (Invocation[M, ?] ~> F) {
        override def apply[A](invocation: Invocation[M, A]): F[A] = {
          val (bytes, decoder) = invocation.invoke(encoder)
          for {
            commandId <- F.delay(CommandId(UUID.randomUUID()))
            waitForResult <- registry.defer(commandId)
            envelope = CommandEnvelope(commandId, selfAddress, key, bytes.array())
            _ <- commands.enqueue1(envelope)
            responseBytes <- waitForResult
            out <- F.fromEither(decoder.decode(ByteBuffer.wrap(responseBytes)))
          } yield out
        }
      })
    }
  }

}

object Runtime {
  final case class CommandResponse(commandId: CommandId, bytes: Array[Byte])
  object CommandResponse {
    import boopickle.Default._
    implicit def entityDecoder[F[_]: Sync]: EntityDecoder[F, CommandResponse] =
      booOf[F, CommandResponse]
    implicit def entityEncoder[F[_]: Applicative]: EntityEncoder[F, CommandResponse] =
      booEncoderOf[F, CommandResponse]
  }
  final case class CommandId(value: UUID) extends AnyVal
  final case class CommandEnvelope[I, K](commandId: CommandId,
                                      replyTo: I,
                                      key: K,
                                      bytes: Array[Byte])
  private[queue] final case class ResponseEnvelope[K](commandId: CommandId, bytes: Array[Byte])

  def create[F[_]: ConcurrentEffect: ContextShift: Timer](
    requestTimeout: FiniteDuration,
    idleTimeout: FiniteDuration
  ): F[Runtime[F]] =
    for {
      kvs <- HashMapKeyValueStore.create[F, CommandId, StoreItem[F, Array[Byte]]]
      registry = DeferredRegistry(requestTimeout, kvs)
    } yield new Runtime(idleTimeout, registry)
}
