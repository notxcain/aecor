package aecor.core.aggregate

import aecor.core.message.Correlation
import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.pattern._
import akka.util.Timeout

import scala.concurrent.Future
import scala.reflect.ClassTag

object AggregateSharding {
  def apply(actorSystem: ActorSystem): AggregateSharding = new AggregateSharding(actorSystem)
}

class AggregateSharding(actorSystem: ActorSystem) {
  class StartRegion[Aggregate](aggregate: Aggregate) {
    def apply[State, Command, Event, Rejection, EventBus]
    ()
    (implicit
     contract: CommandContract.Aux[Aggregate, Command, Rejection],
     behavior: AggregateBehavior[Aggregate, State, Command, Event, Rejection],
     State: ClassTag[State],
     Command: ClassTag[Command],
     Event: ClassTag[Event],
     correlation: Correlation[Command],
     entityName: AggregateName[Aggregate]
    ): AggregateRef[Aggregate] = {

      val settings = new AggregateShardingSettings(actorSystem.settings.config.getConfig("aecor.aggregate"))

      val props = AggregateActor.props(
        entityName.value,
        behavior.initialState(aggregate),
        behavior.commandHandler(aggregate),
        behavior.eventProjector(aggregate),
        settings.idleTimeout(entityName.value)
      )

      val shardRegionRef = ClusterSharding(actorSystem).start(
        typeName = entityName.value,
        entityProps = props,
        settings = ClusterShardingSettings(actorSystem).withRememberEntities(false),
        extractEntityId = AggregateActor.extractEntityId[Command],
        extractShardId = AggregateActor.extractShardId[Command](settings.numberOfShards)
      )

      new AggregateRef[Aggregate] {
        implicit val askTimeout = Timeout(settings.askTimeout)
        import actorSystem.dispatcher

        override private[aecor] val actorRef: ActorRef = shardRegionRef

        override def handle[C](idempotencyKey: String, command: C)(implicit contract: CommandContract[Aggregate, C]): Future[Result[contract.Rejection]] =
          (actorRef ? HandleCommand(CommandId(idempotencyKey), command)).mapTo[AggregateResponse[contract.Rejection]].map(_.result)
      }
    }
  }
  def start[Entity](entity: Entity): StartRegion[Entity] = new StartRegion(entity)
}
