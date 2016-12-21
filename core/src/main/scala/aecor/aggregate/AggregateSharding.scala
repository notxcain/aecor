package aecor.aggregate

import aecor.aggregate.AggregateActor.Tagger
import aecor.aggregate.AggregateSharding.HandleCommand
import aecor.behavior.Behavior
import aecor.serialization.{ PersistentDecoder, PersistentEncoder }
import akka.actor.ActorSystem
import akka.cluster.sharding.{ ClusterSharding, ShardRegion }
import akka.pattern.ask
import akka.util.Timeout
import cats.~>

import scala.concurrent.Future

object AggregateSharding {
  def apply(system: ActorSystem): AggregateSharding =
    new AggregateSharding(system)

  private final case class HandleCommand[C[_], A](entityId: String, command: C[A])
}

class AggregateSharding(system: ActorSystem) {
  def start[Command[_], State, Event: PersistentEncoder: PersistentDecoder](
    behavior: Behavior[Command, State, Event],
    entityName: String,
    correlation: Correlation[Command],
    snapshotPolicy: SnapshotPolicy[State],
    settings: AggregateShardingSettings = AggregateShardingSettings(system)
  ): Command ~> Future = {

    val props = AggregateActor.props(
      behavior,
      entityName,
      Identity.FromPathName,
      snapshotPolicy,
      Tagger.const(entityName),
      settings.idleTimeout(entityName)
    )

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

    new (Command ~> Future) {
      implicit private val timeout = Timeout(settings.askTimeout)
      override def apply[A](fa: Command[A]): Future[A] =
        (shardRegionRef ? HandleCommand(correlation(fa), fa)).asInstanceOf[Future[A]]
    }
  }
}
