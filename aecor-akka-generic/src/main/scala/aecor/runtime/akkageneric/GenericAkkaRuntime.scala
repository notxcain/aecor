package aecor.runtime.akkageneric

import aecor.data.{ Behavior, Correlation }
import aecor.effect.{ Async, Capture }
import aecor.runtime.akkageneric.GenericAkkaRuntime.CorrelatedCommand
import akka.actor.ActorSystem
import akka.cluster.sharding.{ ClusterSharding, ShardRegion }
import akka.pattern._
import akka.util.Timeout
import cats.~>

import scala.concurrent.Future

object GenericAkkaRuntime {
  def apply[F[_]: Async: Capture](system: ActorSystem): GenericAkkaRuntime[F] =
    new GenericAkkaRuntime(system)
  private final case class CorrelatedCommand[A](entityId: String, command: A)
}

class GenericAkkaRuntime[F[_]: Async: Capture](system: ActorSystem) {
  def start[Op[_]](
    typeName: String,
    correlation: Correlation[Op],
    behavior: Behavior[F, Op],
    settings: GenericAkkaRuntimeSettings = GenericAkkaRuntimeSettings.default(system)
  ): F[Op ~> F] =
    Capture[F]
      .capture {
        val numberOfShards = settings.numberOfShards

        val extractEntityId: ShardRegion.ExtractEntityId = {
          case CorrelatedCommand(entityId, c) =>
            (entityId, GenericAkkaRuntimeActor.PerformOp(c.asInstanceOf[Op[_]]))
        }

        val extractShardId: ShardRegion.ExtractShardId = {
          case CorrelatedCommand(entityId, _) =>
            (scala.math.abs(entityId.hashCode) % numberOfShards).toString
          case other => throw new IllegalArgumentException(s"Unexpected message [$other]")
        }

        val props = GenericAkkaRuntimeActor.props(behavior, settings.idleTimeout)

        val shardRegionRef = ClusterSharding(system).start(
          typeName = typeName,
          entityProps = props,
          settings = settings.clusterShardingSettings,
          extractEntityId = extractEntityId,
          extractShardId = extractShardId
        )

        implicit val timeout = Timeout(settings.askTimeout)
        new (Op ~> F) {
          override def apply[A](fa: Op[A]): F[A] = Capture[F].captureFuture {
            (shardRegionRef ? CorrelatedCommand(correlation(fa), fa)).asInstanceOf[Future[A]]
          }
        }
      }
}
