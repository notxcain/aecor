package aecor.runtime.akkageneric

import java.nio.ByteBuffer

import io.aecor.liberator.Invocation
import aecor.data.Behavior
import aecor.encoding.{ KeyDecoder, KeyEncoder }
import aecor.encoding.WireProtocol
import aecor.runtime.akkageneric.GenericAkkaRuntime.KeyedCommand
import aecor.runtime.akkageneric.GenericAkkaRuntimeActor.CommandResult
import aecor.runtime.akkageneric.serialization.Message
import akka.actor.ActorSystem
import akka.cluster.sharding.{ ClusterSharding, ShardRegion }
import akka.pattern._
import akka.util.Timeout
import cats.effect.Effect
import cats.~>
import aecor.util.effect._
import cats.implicits._

import scala.concurrent.Future

object GenericAkkaRuntime {
  def apply(system: ActorSystem): GenericAkkaRuntime =
    new GenericAkkaRuntime(system)
  private[akkageneric] final case class KeyedCommand(key: String, bytes: ByteBuffer) extends Message
}

final class GenericAkkaRuntime private (system: ActorSystem) {
  def deploy[K: KeyEncoder: KeyDecoder, M[_[_]], F[_]](
    typeName: String,
    createBehavior: K => Behavior[M, F],
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

      val props = GenericAkkaRuntimeActor.props(createBehavior, settings.idleTimeout)

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
              Effect[F]
                .fromFuture {
                  (shardRegionRef ? KeyedCommand(keyEncoder(key), bytes.asReadOnlyBuffer()))
                    .asInstanceOf[Future[CommandResult]]
                }
                .map(_.bytes)
                .map(decoder.decode)
                .flatMap(F.fromEither)
            }
          }
        }
    }
}
