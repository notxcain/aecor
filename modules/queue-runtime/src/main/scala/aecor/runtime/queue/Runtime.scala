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

class Runtime[F[_], I] private[queue] (
                                    idleTimeout: FiniteDuration,
                                    registry: DeferredRegistry[F, CommandId, Array[Byte]], clientServer: ClientServer[F, I, CommandResponse]
)(implicit
  F: ConcurrentEffect[F],
  timer: Timer[F])
    extends Http4sDsl[F] {

  def run[K, M[_[_]]](
    name: EntityName,
    create: K => F[M[F]],
    commands: PartitionedQueue[F, EntityName, CommandEnvelope[I, K]]
  )(implicit M: WireProtocol[M]): Resource[F, K => M[F]] = {

    def startCommandProcessor(
      create: K => F[M[F]],
      sendResponse: (I, CommandResponse) => F[Unit]
    ): Resource[F, Unit] =
      Resource[F, Unit] {
        commands
          .subscribe(name)
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
                case x @ CommandEnvelope(commandId, replyTo, key, bytes) =>
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
      cs <- clientServer.start(name)(body => registry.fulfill(body.commandId, body.bytes))
      _ <- startCommandProcessor(create, cs._2)
      encoder = M.encoder
    } yield { key: K =>
      M.mapInvocations(new (Invocation[M, ?] ~> F) {
        private val selfAddress = cs._1
        override def apply[A](invocation: Invocation[M, A]): F[A] = {
          val (bytes, decoder) = invocation.invoke(encoder)
          for {
            commandId <- F.delay(CommandId(UUID.randomUUID()))
            waitForResult <- registry.defer(commandId)
            envelope = CommandEnvelope(commandId, selfAddress, key, bytes.array())
            _ <- commands.enqueue(envelope)
            responseBytes <- waitForResult
            out <- F.fromEither(decoder.decode(ByteBuffer.wrap(responseBytes)))
          } yield out
        }
      })
    }
  }

}

object Runtime {
  final case class EntityName(value: String) extends AnyVal
  private[queue] final case class CommandResponse(commandId: CommandId, bytes: Array[Byte])
  private[queue] object CommandResponse {
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

  def create[F[_]: ConcurrentEffect: ContextShift: Timer, I](
    requestTimeout: FiniteDuration,
    idleTimeout: FiniteDuration,
    clientServer: ClientServer[F, I, CommandResponse]
  ): F[Runtime[F, I]] =
    for {
      kvs <- HashMapKeyValueStore.create[F, CommandId, StoreItem[F, Array[Byte]]]
      registry = DeferredRegistry(requestTimeout, kvs)
    } yield new Runtime(idleTimeout, registry, clientServer)
}
