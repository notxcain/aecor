package aecor.runtime.akkageneric

import aecor.data.Behavior
import aecor.encoding.{ KeyDecoder, KeyEncoder }
import aecor.runtime.akkageneric.GenericAkkaRuntime.CorrelatedCommand
import akka.actor.ActorSystem
import akka.cluster.sharding.{ ClusterSharding, ShardRegion }
import akka.pattern._
import akka.util.Timeout
import cats.effect.Effect
import cats.~>
import aecor.util.effect._
import scala.concurrent.Future

object GenericAkkaRuntime {
  def apply[F[_]: Effect](system: ActorSystem): GenericAkkaRuntime[F] =
    new GenericAkkaRuntime(system)
  private final case class CorrelatedCommand[I, A](correlationId: String, command: A)
}

final class GenericAkkaRuntime[F[_]: Effect] private (system: ActorSystem) {
  def deploy[I: KeyEncoder: KeyDecoder, Op[_]](
    typeName: String,
    createBehavior: I => Behavior[F, Op],
    settings: GenericAkkaRuntimeSettings = GenericAkkaRuntimeSettings.default(system)
  ): F[I => Op ~> F] =
    Effect[F].delay {

      import system.dispatcher

      val numberOfShards = settings.numberOfShards

      val extractEntityId: ShardRegion.ExtractEntityId = {
        case CorrelatedCommand(entityId, c) =>
          (entityId, GenericAkkaRuntimeActor.PerformOp(c.asInstanceOf[Op[_]]))
      }

      val extractShardId: ShardRegion.ExtractShardId = {
        case CorrelatedCommand(correlationId, _) =>
          (scala.math.abs(correlationId.hashCode) % numberOfShards).toString
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

      val keyEncoder = KeyEncoder[I]

      i =>
        new (Op ~> F) {
          override def apply[A](fa: Op[A]): F[A] = Effect[F].fromFuture {
            (shardRegionRef ? CorrelatedCommand(keyEncoder(i), fa)).asInstanceOf[Future[A]]
          }
        }
    }
}
