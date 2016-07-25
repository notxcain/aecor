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
    def apply[Command, Event, Rejection, EventBus]
    ()
    (implicit
      aab: AggregateBehavior.Aux[Aggregate, Command, Rejection, Event],
     Command: ClassTag[Command],
     Event: ClassTag[Event],
     correlation: Correlation[Command],
     entityName: AggregateName[Aggregate]
    ): AggregateRef[Aggregate] = {

      val settings = new AggregateShardingSettings(actorSystem.settings.config.getConfig("aecor.aggregate"))

      scala.reflect.classTag[AggregateCommand[Command]]

      val props = EventsourcedActor.props(DefaultBehavior(aggregate))(entityName.value, settings.idleTimeout(entityName.value))

      val shardRegionRef = ClusterSharding(actorSystem).start(
        typeName = entityName.value,
        entityProps = props,
        settings = ClusterShardingSettings(actorSystem).withRememberEntities(false),
        extractEntityId = EventsourcedActor.extractEntityId[AggregateCommand[Command]],
        extractShardId = EventsourcedActor.extractShardId[AggregateCommand[Command]](settings.numberOfShards)
      )

      new AggregateRef[Aggregate] {
        implicit val askTimeout = Timeout(settings.askTimeout)
        import actorSystem.dispatcher

        override private[aecor] val actorRef: ActorRef = shardRegionRef

        override def handle[C](idempotencyKey: String, command: C)(implicit contract: CommandContract[Aggregate, C]): Future[Result[contract.Rejection]] =
          (actorRef ? AggregateCommand(CommandId(idempotencyKey), command)).mapTo[AggregateResponse[contract.Rejection]].map(_.result)
      }
    }
  }
  def start[Entity](entity: Entity): StartRegion[Entity] = new StartRegion(entity)
}
