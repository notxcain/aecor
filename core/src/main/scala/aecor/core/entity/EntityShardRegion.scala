package aecor.core.entity

import aecor.core.message.{Correlation, ExtractShardId, Message, MessageId}
import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.pattern._
import akka.util.Timeout

import scala.concurrent.Future
import scala.reflect.ClassTag

object EntityShardRegion {
  def apply(actorSystem: ActorSystem): EntityShardRegion = new EntityShardRegion(actorSystem)
}

class EntityShardRegion(actorSystem: ActorSystem) {
  class StartRegion[Entity](entity: Entity) {
    def apply[State, Command, Event, Rejection, EventBus]
    ()
    (implicit
     contract: CommandContract.Aux[Entity, Command, Rejection],
     behavior: EntityBehavior[Entity, State, Command, Event, Rejection],
     State: ClassTag[State],
     Command: ClassTag[Command],
     Event: ClassTag[Event],
     correlation: Correlation[Command],
     entityName: EntityName[Entity]
    ): EntityRef[Entity] = {

      val config = new EntityShardRegionConfig(actorSystem.settings.config.getConfig("aecor.entity"))


      def extractEntityId: ShardRegion.ExtractEntityId = {
        case m @ Message(_, c: Command, _) ⇒ (correlation(c), m)
      }

      val numberOfShards = config.numberOfShards
      def extractShardId: ShardRegion.ExtractShardId = {
        case m @ Message(_, c: Command, _) => ExtractShardId(correlation(c), numberOfShards)
      }

      val props = EntityActor.props(
        entityName.value,
        behavior.initialState(entity),
        behavior.commandHandler(entity),
        behavior.eventProjector(entity),
        config.idleTimeout(entityName.value)
      )

      val shardRegionRef = ClusterSharding(actorSystem).start(
        typeName = entityName.value,
        entityProps = props,
        settings = ClusterShardingSettings(actorSystem).withRememberEntities(false),
        extractEntityId = extractEntityId,
        extractShardId = extractShardId
      )

      new EntityRef[Entity] {
        implicit val askTimeout = Timeout(config.askTimeout)
        import actorSystem.dispatcher

        override private[aecor] val actorRef: ActorRef = shardRegionRef

        override def handle[C](idempotencyKey: String, command: C)(implicit contract: CommandContract[Entity, C]): Future[Result[contract.Rejection]] =
          (actorRef ? Message(MessageId(idempotencyKey), command, NotUsed)).mapTo[EntityResponse[contract.Rejection, NotUsed]].map(_.result)
      }
    }
  }
  def start[Entity](entity: Entity): StartRegion[Entity] = new StartRegion(entity)
}
