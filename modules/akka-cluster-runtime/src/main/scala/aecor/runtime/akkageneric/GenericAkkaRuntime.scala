package aecor.runtime.akkageneric

import aecor.encoding.syntax._
import aecor.encoding.WireProtocol.Encoded
import aecor.encoding.{ KeyDecoder, KeyEncoder, WireProtocol }
import aecor.runtime.akkageneric.GenericAkkaRuntime.KeyedCommand
import aecor.runtime.akkageneric.GenericAkkaRuntimeActor.CommandResult
import aecor.runtime.akkageneric.serialization.Message
import akka.actor.ActorSystem
import akka.cluster.sharding.{ ClusterSharding, ShardRegion }
import akka.pattern._
import akka.util.Timeout
import cats.effect.kernel.{ Async, Resource }
import cats.syntax.all._
import cats.tagless.FunctorK
import cats.tagless.syntax.functorK._
import cats.~>
import scodec.bits.BitVector

object GenericAkkaRuntime {
  def apply(system: ActorSystem): GenericAkkaRuntime =
    new GenericAkkaRuntime(system)
  private[akkageneric] final case class KeyedCommand(key: String, bytes: BitVector) extends Message
}

final class GenericAkkaRuntime private (system: ActorSystem) {
  def runBehavior[K: KeyEncoder: KeyDecoder, M[_[_]]: FunctorK, F[_]](
      typeName: String,
      createBehavior: K => F[M[F]],
      settings: GenericAkkaRuntimeSettings = GenericAkkaRuntimeSettings.default(system)
  )(implicit M: WireProtocol[M], F: Async[F]): Resource[F, K => M[F]] =
    GenericAkkaRuntimeActor
      .props[K, M, F](createBehavior, settings.idleTimeout)
      .map { props =>
        val extractEntityId: ShardRegion.ExtractEntityId = { case KeyedCommand(entityId, c) =>
          (entityId, GenericAkkaRuntimeActor.Command(c))
        }

        val numberOfShards = settings.numberOfShards

        val extractShardId: ShardRegion.ExtractShardId = {
          case KeyedCommand(key, _) =>
            String.valueOf(scala.math.abs(key.hashCode) % numberOfShards)
          case other => throw new IllegalArgumentException(s"Unexpected message [$other]")
        }

        val shardRegion = ClusterSharding(system).start(
          typeName = typeName,
          entityProps = props,
          settings = settings.clusterShardingSettings,
          extractEntityId = extractEntityId,
          extractShardId = extractShardId
        )

        val keyEncoder = KeyEncoder[K]

        key =>
          M.encoder.mapK(new (Encoded ~> F) {
            implicit val askTimeout: Timeout = Timeout(settings.askTimeout)

            override def apply[A](fa: Encoded[A]): F[A] = F.defer {
              val (bytes, decoder) = fa

              Async[F]
                .fromFuture(Async[F].delay(shardRegion ? KeyedCommand(keyEncoder(key), bytes)))
                .flatMap {
                  case result: CommandResult =>
                    decoder.decodeValue(result.bytes).lift[F]
                  case other =>
                    F.raiseError(
                      new IllegalArgumentException(
                        s"Unexpected response [$other] from shard region"
                      )
                    )
                }
            }
          })
      }
}
