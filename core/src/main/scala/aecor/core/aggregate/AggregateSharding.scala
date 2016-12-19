package aecor.core.aggregate

import aecor.core.aggregate.AggregateActor.Tagger
import aecor.core.aggregate.behavior.Behavior
import akka.actor.ActorSystem
import akka.cluster.sharding.{ClusterSharding, ShardRegion}
import cats.~>

import scala.concurrent.Future

object AggregateSharding {
  def apply(system: ActorSystem): AggregateSharding =
    new AggregateSharding(system)
}

class AggregateSharding(system: ActorSystem) {
  def start[Command[_], State, Event](
      behavior: Behavior[Command, State, Event],
      entityName: String,
      correlation: Correlation[Command],
      settings: AggregateShardingSettings = AggregateShardingSettings(system))
    : Command ~> Future = {

    val props = AggregateActor.props(
      behavior,
      entityName,
      Identity.FromPathName,
      settings.snapshotPolicy(entityName),
      Tagger.const(entityName),
      settings.idleTimeout(entityName)
    )

    def extractEntityId: ShardRegion.ExtractEntityId = {
      case a => (correlation(a.asInstanceOf[Command[_]]), a)
    }

    val numberOfShards = settings.numberOfShards
    def extractShardId: ShardRegion.ExtractShardId = { a =>
      (scala.math.abs(correlation(a.asInstanceOf[Command[_]]).hashCode) % numberOfShards).toString
    }

    val shardRegionRef = ClusterSharding(system).start(
      typeName = entityName,
      entityProps = props,
      settings = settings.clusterShardingSettings,
      extractEntityId = extractEntityId,
      extractShardId = extractShardId
    )

    new RegionRef[Command](shardRegionRef, settings.askTimeout)
  }
}
