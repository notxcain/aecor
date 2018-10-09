package aecor.runtime.queue

import java.net.InetSocketAddress
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
import cats.{ Applicative, ~> }
import fs2.concurrent.Enqueue
import io.aecor.liberator.Invocation
import fs2._
import org.http4s.Uri.{ Authority, Scheme }
import org.http4s.booPickle._
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Server
import org.http4s.server.blaze.BlazeBuilder
import org.http4s.{ EntityDecoder, EntityEncoder, HttpRoutes, Method, Request, Uri }

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

class Runtime[F[_]] private[queue] (selfAddress: MemberAddress,
                                       idleTimeout: FiniteDuration,
                                       registry: DeferredRegistry[F, CommandId, Array[Byte]],
                                       )(
  implicit
  F: ConcurrentEffect[F],
  context: ContextShift[F],
  timer: Timer[F],
  executionContext: ExecutionContext
) extends Http4sDsl[F] {

  def deploy[K, M[_[_]]](name: String,
                      create: K => F[M[F]], commands: Enqueue[F, CommandEnvelope[K]],
                      commandPartitions: Stream[F, Stream[F, CommandEnvelope[K]]])(implicit M: WireProtocol[M]): Resource[F, K => M[F]] = {
    val routes: HttpRoutes[F] = HttpRoutes.of[F] {
      case req @ POST -> Root =>
        for {
          body <- req.as[CommandResponse]
          _ <- registry.fulfill(body.commandId, body.bytes)
          resp <- Accepted()
        } yield resp

    }

    def createResponseSender(client: Client[F]): ResponseSender[F] = {
      case (MemberAddress(address), response) =>
        val uri = Uri(
          Some(Scheme.http),
          Some(Authority(host = Uri.IPv4(address.getHostString), port = Some(address.getPort))),
          path = s"/$name"
        )
        client.fetch(Request[F](Method.POST, uri).withEntity(response))(_ => ().pure[F])
    }

    def startResponseSender: Resource[F, ResponseSender[F]] =
      BlazeClientBuilder[F](executionContext).resource
        .flatMap(c => Resource.liftF(createResponseSender(c).pure[F]))

    def startResponseProcessor: Resource[F, Server[F]] =
      BlazeBuilder[F].bindSocketAddress(selfAddress.value).mountService(routes, "/").resource

    def startCommandProcessor(
      create: K => F[M[F]],
      sendResponse: (MemberAddress, CommandResponse) => F[Unit]
    ): Resource[F, Unit] =
      Resource[F, Unit] {
        commandPartitions
          .map { commands =>
            val startShard =
              ActorShard.create[F, K, (CommandId, MemberAddress, PairE[Invocation[M, ?], Encoder])](
                idleTimeout
              ) { key =>
                Actor.create[F, (CommandId, MemberAddress, PairE[Invocation[M, ?], Encoder])] { _ =>
                  create(key).map { mf =>
                    Receive[(CommandId, MemberAddress, PairE[Invocation[M, ?], Encoder])] {
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
      responseSender <- startResponseSender
      _ <- startResponseProcessor
      _ <- startCommandProcessor(create, responseSender)
      encoder = M.encoder
    } yield { key: K =>
      M.mapInvocations(new (Invocation[M, ?] ~> F) {
        override def apply[A](invocation: Invocation[M, A]): F[A] = {
          val (bytes, decoder) = invocation.invoke(encoder)
          for {
            commandId <- F.delay(CommandId(UUID.randomUUID()))
            waitForResult <- registry.defer(commandId)
            _ <- commands.enqueue1(CommandEnvelope(commandId, selfAddress, key, bytes.array()))
            responseBytes <- waitForResult
            out <- F.fromEither(decoder.decode(ByteBuffer.wrap(responseBytes)))
          } yield out
        }
      })
    }
  }

}

object Runtime {
  type ResponseSender[F[_]] = (MemberAddress, CommandResponse) => F[Unit]
  final case class MemberAddress(value: InetSocketAddress) extends AnyVal
  object MemberAddress {
    def apply(host: String, port: Int): MemberAddress = MemberAddress(new InetSocketAddress(host, port))
  }
  private[queue] final case class CommandResponse(commandId: CommandId, bytes: Array[Byte])
  private[queue] object CommandResponse {
    import boopickle.Default._
    implicit def entityDecoder[F[_]: Sync]: EntityDecoder[F, CommandResponse] =
      booOf[F, CommandResponse]
    implicit def entityEncoder[F[_]: Applicative]: EntityEncoder[F, CommandResponse] =
      booEncoderOf[F, CommandResponse]
  }
  final case class CommandId(value: UUID) extends AnyVal
  final case class CommandEnvelope[K](commandId: CommandId,
                                      replyTo: MemberAddress,
                                      key: K,
                                      bytes: Array[Byte])
  private[queue] final case class ResponseEnvelope[K](commandId: CommandId, bytes: Array[Byte])

  def create[F[_]: ConcurrentEffect: ContextShift: Timer](
    selfAddress: MemberAddress,
    requestTimeout: FiniteDuration,
    idleTimeout: FiniteDuration
  )(implicit executionContext: ExecutionContext): F[Runtime[F]] =
    for {
      kvs <- HashMapKeyValueStore.create[F, CommandId, StoreItem[F, Array[Byte]]]
      registry = DeferredRegistry(requestTimeout, kvs)
    } yield new Runtime(selfAddress, idleTimeout, registry)
}
