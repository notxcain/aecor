package aecor.aggregate.runtime

import aecor.aggregate._
import aecor.aggregate.runtime.GenericAkkaRuntime.CorrelatedCommand
import aecor.aggregate.runtime.behavior.Behavior
import akka.actor.ActorSystem
import akka.cluster.sharding.{ ClusterSharding, ShardRegion }
import akka.pattern._
import akka.util.Timeout
import cats.{ Functor, ~> }

import cats.implicits._
import scala.concurrent.Future

object GenericAkkaRuntime {
  def apply(system: ActorSystem): GenericAkkaRuntime = new GenericAkkaRuntime(system)
  private final case class CorrelatedCommand[A](entityId: String, command: A)
}

class GenericAkkaRuntime(system: ActorSystem) {
  def start[Op[_], F[_]: Async: CaptureFuture: Functor: Capture](
    entityName: String,
    correlation: Correlation[Op],
    behavior: Behavior[Op, F],
    settings: AkkaRuntimeSettings = AkkaRuntimeSettings.default(system)
  ): F[Op ~> F] =
    Capture[F]
      .capture {
        val numberOfShards = settings.numberOfShards

        val extractEntityId: ShardRegion.ExtractEntityId = {
          case CorrelatedCommand(entityId, c) =>
            (entityId, RuntimeActor.PerformOp(c.asInstanceOf[Op[_]]))
        }

        val extractShardId: ShardRegion.ExtractShardId = {
          case CorrelatedCommand(entityId, _) =>
            (scala.math.abs(entityId.hashCode) % numberOfShards).toString
        }
        val props = RuntimeActor.props(behavior, settings.idleTimeout)
        ClusterSharding(system).start(
          typeName = entityName,
          entityProps = props,
          settings = settings.clusterShardingSettings,
          extractEntityId = extractEntityId,
          extractShardId = extractShardId
        )
      }
      .map { shardRegionRef =>
        new (Op ~> F) {
          implicit private val timeout = Timeout(settings.askTimeout)
          override def apply[A](fa: Op[A]): F[A] = CaptureFuture[F].captureF {
            (shardRegionRef ? CorrelatedCommand(correlation(fa), fa)).asInstanceOf[Future[A]]
          }
        }
      }
}
