package aecor.runtime.akkacluster

import aecor.data.{ Behavior, Correlation }
import aecor.effect.{ Async, Capture, CaptureFuture }
import aecor.runtime.akkacluster.GenericAkkaRuntime.CorrelatedCommand
import akka.actor.ActorSystem
import akka.cluster.sharding.{ ClusterSharding, ShardRegion }
import akka.pattern._
import akka.util.Timeout
import cats.implicits._
import cats.{ Functor, ~> }

import scala.concurrent.Future

object GenericAkkaRuntime {
  def apply[F[_]: Async: CaptureFuture: Functor: Capture](
    system: ActorSystem
  ): GenericAkkaRuntime[F] =
    new GenericAkkaRuntime(system)
  private final case class CorrelatedCommand[A](entityId: String, command: A)
}

class GenericAkkaRuntime[F[_]: Async: CaptureFuture: Functor: Capture](system: ActorSystem) {
  def start[Op[_]](entityName: String,
                   correlation: Correlation[Op],
                   behavior: Behavior[F, Op],
                   settings: GenericAkkaRuntimeSettings =
                     GenericAkkaRuntimeSettings.default(system)): F[Op ~> F] =
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
        }
        val props = GenericAkkaRuntimeActor.props(behavior, settings.idleTimeout)
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
          override def apply[A](fa: Op[A]): F[A] = CaptureFuture[F].captureFuture {
            (shardRegionRef ? CorrelatedCommand(correlation(fa), fa)).asInstanceOf[Future[A]]
          }
        }
      }
}
