package aecor.aggregate

import aecor.aggregate.AkkaRuntime.CorrelatedCommand
import aecor.aggregate.serialization.{ PersistentDecoder, PersistentEncoder }
import aecor.data.{ Folded, Handler }
import akka.actor.ActorSystem
import akka.cluster.sharding.{ ClusterSharding, ShardRegion }
import akka.pattern.ask
import akka.util.Timeout
import cats.~>

import scala.concurrent.Future

object AkkaRuntime {
  def apply(system: ActorSystem): AkkaRuntime =
    new AkkaRuntime(system)

  private final case class CorrelatedCommand[C[_], A](entityId: String, command: C[A])
}

class AkkaRuntime(system: ActorSystem) {
  def start[Command[_], State, Event: PersistentEncoder: PersistentDecoder](
    entityName: String,
    behavior: Command ~> Handler[State, Event, ?],
    correlation: Correlation[Command],
    tagging: Tagging[Event],
    snapshotPolicy: SnapshotPolicy[State] = SnapshotPolicy.never,
    settings: AkkaRuntimeSettings = AkkaRuntimeSettings.default(system)
  )(implicit folder: Folder[Folded, Event, State]): Command ~> Future = {

    val props = AggregateActor.props(
      entityName,
      behavior,
      Identity.FromPathName,
      snapshotPolicy,
      tagging,
      settings.idleTimeout
    )

    def extractEntityId: ShardRegion.ExtractEntityId = {
      case CorrelatedCommand(entityId, c) =>
        (entityId, AggregateActor.HandleCommand(c))
    }

    val numberOfShards = settings.numberOfShards

    def extractShardId: ShardRegion.ExtractShardId = {
      case CorrelatedCommand(entityId, _) =>
        (scala.math.abs(entityId.hashCode) % numberOfShards).toString
      case x =>
        throw new IllegalArgumentException(s"Expected message of type CorrelatedCommand, got [$x]")
    }

    val shardRegionRef = ClusterSharding(system).start(
      typeName = entityName,
      entityProps = props,
      settings = settings.clusterShardingSettings,
      extractEntityId = extractEntityId,
      extractShardId = extractShardId
    )

    new (Command ~> Future) {
      implicit private val timeout = Timeout(settings.askTimeout)
      override def apply[A](fa: Command[A]): Future[A] =
        (shardRegionRef ? CorrelatedCommand(correlation(fa), fa)).asInstanceOf[Future[A]]
    }
  }
}
