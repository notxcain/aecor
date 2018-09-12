package aecor.runtime.akkageneric

import java.nio.ByteBuffer

import aecor.encoding.{KeyDecoder, KeyEncoder, WireProtocol}
import aecor.runtime.akkageneric.GenericAkkaRuntime.KeyedCommand
import aecor.runtime.akkageneric.GenericAkkaRuntimeActor.CommandResult
import aecor.runtime.akkageneric.serialization.Message
import aecor.util.effect._
import akka.actor.ActorSystem
import akka.cluster.sharding.{ClusterSharding, ShardRegion}
import akka.pattern._
import akka.util.Timeout
import cats.effect.Effect
import cats.implicits._
import cats.~>
import io.aecor.liberator.Invocation

object GenericAkkaRuntime {
  def apply(system: ActorSystem): GenericAkkaRuntime =
    new GenericAkkaRuntime(system)
  private[akkageneric] final case class KeyedCommand(key: String, bytes: ByteBuffer) extends Message
}

final class GenericAkkaRuntime private (system: ActorSystem) {
  def runBehavior[K: KeyEncoder: KeyDecoder, M[_[_]], F[_]](
    typeName: String,
    createBehavior: K => F[M[F]],
    settings: GenericAkkaRuntimeSettings = GenericAkkaRuntimeSettings.default(system)
  )(implicit M: WireProtocol[M], F: Effect[F]): F[K => M[F]] =
    F.delay {
      val numberOfShards = settings.numberOfShards

      val extractEntityId: ShardRegion.ExtractEntityId = {
        case KeyedCommand(entityId, c) =>
          (entityId, GenericAkkaRuntimeActor.Command(c))
      }

      val extractShardId: ShardRegion.ExtractShardId = {
        case KeyedCommand(key, _) =>
          String.valueOf(scala.math.abs(key.hashCode) % numberOfShards)
        case other => throw new IllegalArgumentException(s"Unexpected message [$other]")
      }

      val props = GenericAkkaRuntimeActor.props[K, M, F](createBehavior, settings.idleTimeout)

      val shardRegionRef = ClusterSharding(system).start(
        typeName = typeName,
        entityProps = props,
        settings = settings.clusterShardingSettings,
        extractEntityId = extractEntityId,
        extractShardId = extractShardId
      )

      implicit val timeout = Timeout(settings.askTimeout)

      val keyEncoder = KeyEncoder[K]

      key =>
        M.mapInvocations {
          new (Invocation[M, ?] ~> F) {
            override def apply[A](fa: Invocation[M, A]): F[A] = F.suspend {
              val (bytes, decoder) = fa.invoke(M.encoder)
              F.fromFuture {
                  shardRegionRef ? KeyedCommand(keyEncoder(key), bytes.asReadOnlyBuffer())
                }
                .flatMap {
                  case result: CommandResult =>
                    F.fromEither(decoder.decode(result.bytes))
                  case other =>
                    F.raiseError(new IllegalArgumentException(s"Unexpected response [$other] from shard region"))
                }
            }
          }
        }
    }
}
