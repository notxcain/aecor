package aecor.core.aggregate

import aecor.core.actor.EventsourcedActor
import aecor.core.message.Correlation
import akka.actor.ActorSystem
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.pattern._
import akka.util.Timeout

import scala.concurrent.Future
import scala.reflect.ClassTag

object AggregateSharding {
  def apply(system: ActorSystem): AggregateSharding = new AggregateSharding(system)
}

class AggregateSharding(system: ActorSystem) {
  def start[Aggregate, Command, Event, Rejection, EventBus]
  (aggregate: Aggregate)
  (implicit
   aab: AggregateBehavior.Aux[Aggregate, Command, Rejection, Event],
   Command: ClassTag[Command],
   Event: ClassTag[Event],
   correlation: Correlation[Command],
   entityName: AggregateName[Aggregate]
  ): AggregateRef[Aggregate] = {

    val settings = new AggregateShardingSettings(system.settings.config.getConfig("aecor.aggregate"))

    scala.reflect.classTag[AggregateCommand[Command]]

    val props = EventsourcedActor.props(AggregateEventsourcedBehavior(aggregate))(entityName.value, settings.idleTimeout(entityName.value))

    val shardRegionRef = ClusterSharding(system).start(
      typeName = entityName.value,
      entityProps = props,
      settings = ClusterShardingSettings(system).withRememberEntities(false),
      extractEntityId = EventsourcedActor.extractEntityId[AggregateCommand[Command]](a => correlation(a.command)),
      extractShardId = EventsourcedActor.extractShardId[AggregateCommand[Command]](settings.numberOfShards)(a => correlation(a.command))
    )

    new AggregateRef[Aggregate] {
      implicit val askTimeout = Timeout(settings.askTimeout)
      import system.dispatcher
      override def handle[C](idempotencyKey: String, command: C)(implicit contract: CommandContract[Aggregate, C]): Future[Result[contract.Rejection]] =
        (shardRegionRef ? AggregateCommand(CommandId(idempotencyKey), command)).mapTo[AggregateResponse[contract.Rejection]].map(_.result)
    }
  }
}
