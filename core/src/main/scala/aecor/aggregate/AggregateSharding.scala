package aecor.aggregate

import aecor.aggregate.AggregateActor.Tagger
import aecor.behavior.Behavior
import akka.actor.ActorSystem
import akka.cluster.sharding.{ ClusterSharding, ShardRegion }
import akka.pattern.ask
import akka.util.Timeout
import cats.~>

import scala.concurrent.Future

object AggregateSharding {
  def apply(system: ActorSystem): AggregateSharding =
    new AggregateSharding(system)
}

class AggregateSharding(system: ActorSystem) {
  def start[Command[_], State, Event](behavior: Behavior[Command, State, Event],
                                      entityName: String,
                                      correlation: Correlation[Command],
                                      settings: AggregateShardingSettings =
                                        AggregateShardingSettings(system)): Command ~> Future = {

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

    new (Command ~> Future) {
      implicit private val timeout = Timeout(settings.askTimeout)
      override def apply[A](fa: Command[A]): Future[A] =
        (shardRegionRef ? fa).asInstanceOf[Future[A]]
    }
  }
}
