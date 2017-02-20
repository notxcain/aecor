package aecor.aggregate.runtime

import aecor.aggregate._
import aecor.aggregate.runtime.GenericAkkaRuntime.HandleCommand
import aecor.aggregate.runtime.behavior.Behavior
import akka.actor.ActorSystem
import akka.cluster.sharding.{ ClusterSharding, ShardRegion }
import akka.pattern._
import akka.util.Timeout
import cats.{ Functor, ~> }

import scala.concurrent.Future

object GenericAkkaRuntime {
  def apply(system: ActorSystem): GenericAkkaRuntime = new GenericAkkaRuntime(system)
  private final case class HandleCommand[A](entityId: String, command: A)
}

class GenericAkkaRuntime(system: ActorSystem) {
  def start[Op[_], F[_]: Async: Functor](entityName: String,
                                         correlation: Correlation[Op],
                                         behavior: Behavior[Op, F],
                                         settings: AkkaRuntimeSettings =
                                           AkkaRuntimeSettings.default(system)): Op ~> F = {
    val props = RuntimeActor.props(behavior, settings.idleTimeout)

    def extractEntityId: ShardRegion.ExtractEntityId = {
      case HandleCommand(entityId, c) =>
        (entityId, c)
    }

    val numberOfShards = settings.numberOfShards

    def extractShardId: ShardRegion.ExtractShardId = {
      case HandleCommand(entityId, _) =>
        (scala.math.abs(entityId.hashCode) % numberOfShards).toString
    }

    val shardRegionRef = ClusterSharding(system).start(
      typeName = entityName,
      entityProps = props,
      settings = settings.clusterShardingSettings,
      extractEntityId = extractEntityId,
      extractShardId = extractShardId
    )

    new (Op ~> F) {
      implicit private val timeout = Timeout(settings.askTimeout)
      override def apply[A](fa: Op[A]): F[A] =
        Async[F].capture(
          (shardRegionRef ? HandleCommand(correlation(fa), fa)).asInstanceOf[Future[A]]
        )
    }
  }
}
