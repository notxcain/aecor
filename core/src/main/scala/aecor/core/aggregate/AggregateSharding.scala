package aecor.core.aggregate

import aecor.core.message.Correlation
import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.cluster.sharding.{ClusterSharding, ShardRegion}

import scala.reflect.ClassTag
import scala.util.hashing.MurmurHash3

object AggregateSharding extends ExtensionId[AggregateSharding] with ExtensionIdProvider {
  override def get(system: ActorSystem): AggregateSharding = super.get(system)
  override def lookup = AggregateSharding
  override def createExtension(system: ExtendedActorSystem): AggregateSharding  =
    new AggregateSharding(system)
}

class AggregateSharding(system: ExtendedActorSystem) extends Extension {

  def start[Aggregate, Command[_], State, Event]
  (aggregate: Aggregate, settings: AggregateShardingSettings = AggregateShardingSettings(system))
  (implicit
   aab: AggregateBehavior.Aux[Aggregate, Command, State, Event],
   Command: ClassTag[Command[_]],
   Event: ClassTag[Event],
   State: ClassTag[State],
   correlation: Correlation[Command[_]],
   aggregateName: AggregateName[Aggregate]
  ): AggregateRegionRef[Command] = {

    val props = AggregateActor.props(
      aggregate,
      aggregateName.value,
      Identity.FromPathName,
      settings.snapshotPolicy(aggregateName.value),
      settings.idleTimeout(aggregateName.value)
    )

    def extractEntityId: ShardRegion.ExtractEntityId = {
      case a => (correlation(a.asInstanceOf[Command[_]]), a)
    }

    val numberOfShards = settings.numberOfShards
    def extractShardId: ShardRegion.ExtractShardId = {
      a =>
        (scala.math.abs(MurmurHash3.stringHash(correlation(a.asInstanceOf[Command[_]]))) % numberOfShards).toString
    }

    val shardRegionRef = ClusterSharding(system).start(
      typeName = aggregateName.value,
      entityProps = props,
      settings = settings.clusterShardingSettings,
      extractEntityId = extractEntityId,
      extractShardId = extractShardId
    )

    new AggregateRegionRef[Command](system, shardRegionRef, settings.askTimeout)
  }
}
