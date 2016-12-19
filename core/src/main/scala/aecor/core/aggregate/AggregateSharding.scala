package aecor.core.aggregate

import aecor.core.aggregate.AggregateActor.Tagger
import aecor.core.aggregate.AggregateBehavior.Aux
import aecor.core.aggregate.behavior.{Behavior, Handler}
import akka.actor.{
  ExtendedActorSystem,
  Extension,
  ExtensionId,
  ExtensionIdProvider
}
import akka.cluster.sharding.{ClusterSharding, ShardRegion}
import cats.~>

import scala.concurrent.Future
import scala.reflect.ClassTag

object AggregateSharding
    extends ExtensionId[AggregateSharding]
    with ExtensionIdProvider {
  override def lookup = AggregateSharding
  override def createExtension(
      system: ExtendedActorSystem): AggregateSharding =
    new AggregateSharding(system)
}

class AggregateSharding(system: ExtendedActorSystem) extends Extension {

  abstract class MkStart[Command[_]] {
    def apply[Aggregate, State, Event](aggregate: Aggregate,
                                       settings: AggregateShardingSettings =
                                         AggregateShardingSettings(system))(
        implicit aab: AggregateBehavior.Aux[Aggregate, Command, State, Event],
        Command: ClassTag[Command[_]],
        Event: ClassTag[Event],
        State: ClassTag[State],
        correlation: Correlation[Command],
        aggregateName: AggregateName[Aggregate]): Command ~> Future
  }

  def start[Command[_]]: MkStart[Command] = new MkStart[Command] {
    override def apply[Aggregate, State, Event](
        aggregate: Aggregate,
        settings: AggregateShardingSettings)(
        implicit aab: Aux[Aggregate, Command, State, Event],
        Command: ClassTag[Command[_]],
        Event: ClassTag[Event],
        State: ClassTag[State],
        correlation: Correlation[Command],
        aggregateName: AggregateName[Aggregate]): Command ~> Future = {

      val behavior =
        Behavior(
          commandHandler = new (Command ~> Handler[State, Event, ?]) {
            override def apply[A](fa: Command[A]): Handler[State, Event, A] = {
              state =>
                aab.handleCommand(aggregate)(state, fa).swap
            }
          },
          initialState = aab.init,
          projector = aab.applyEvent(_: State, _: Event)
        )

      val props = AggregateActor.props(
        behavior,
        aggregateName.value,
        Identity.FromPathName,
        settings.snapshotPolicy(aggregateName.value),
        Tagger.const(aggregateName.value),
        settings.idleTimeout(aggregateName.value)
      )

      def extractEntityId: ShardRegion.ExtractEntityId = {
        case a => (correlation(a.asInstanceOf[Command[_]]), a)
      }

      val numberOfShards = settings.numberOfShards
      def extractShardId: ShardRegion.ExtractShardId = { a =>
        (scala.math
          .abs(correlation(a.asInstanceOf[Command[_]]).hashCode) % numberOfShards).toString
      }

      val shardRegionRef = ClusterSharding(system).start(
        typeName = aggregateName.value,
        entityProps = props,
        settings = settings.clusterShardingSettings,
        extractEntityId = extractEntityId,
        extractShardId = extractShardId
      )

      new RegionRef[Command](shardRegionRef, settings.askTimeout)
    }
  }
}
