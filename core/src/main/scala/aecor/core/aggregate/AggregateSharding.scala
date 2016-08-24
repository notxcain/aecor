package aecor.core.aggregate

import aecor.core.actor.{EventsourcedEntity, Identity}
import aecor.core.message.Correlation
import akka.actor.ActorSystem
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}

import scala.reflect.ClassTag

object AggregateSharding {
  def apply(system: ActorSystem): AggregateSharding = new AggregateSharding(system)
}

class AggregateSharding(system: ActorSystem) {
  def start[Aggregate, Command[_], State, Event]
  (aggregate: Aggregate)
  (implicit
   aab: AggregateBehavior.Aux[Aggregate, Command, State, Event],
   Command: ClassTag[Command[_]],
   Event: ClassTag[Event],
   State: ClassTag[State],
   correlation: Correlation[Command[_]],
   aggregateName: AggregateName[Aggregate]
  ): AggregateRegionRef[Command] = {

    import system.dispatcher

    val settings = new AggregateShardingSettings(system.settings.config.getConfig("aecor.aggregate"))

    val props = EventsourcedEntity.props(
      AggregateEventsourcedBehavior(aggregate),
      aggregateName.value,
      Identity.FromPathName,
      settings.snapshotPolicy(aggregateName.value),
      settings.idleTimeout(aggregateName.value)
    )

    val shardRegionRef = ClusterSharding(system).start(
      typeName = aggregateName.value,
      entityProps = props,
      settings = ClusterShardingSettings(system).withRememberEntities(false),
      extractEntityId = EventsourcedEntity.extractEntityId[Command[_]](a => correlation(a)),
      extractShardId = EventsourcedEntity.extractShardId[Command[_]](settings.numberOfShards)(a => correlation(a))
    )

    new AggregateRegionRef[Command](system, shardRegionRef, settings.askTimeout)
  }
}
