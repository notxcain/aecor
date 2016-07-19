package aecor.core.entity

import aecor.core.message.{Correlation, ExtractShardId, Message, MessageId}
import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.pattern._
import akka.util.Timeout

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.concurrent.duration._

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

      val config = actorSystem.settings.config

      val numberOfShards = config.getInt("aecor.entity.number-of-shards")

      def extractEntityId: ShardRegion.ExtractEntityId = {
        case m @ Message(_, c: Command, _) ⇒ (correlation(c), m)
      }

      def extractShardId: ShardRegion.ExtractShardId = {
        case m @ Message(_, c: Command, _) => ExtractShardId(correlation(c), numberOfShards)
      }

      val props = EntityActor.props(
        entityName.value,
        behavior.initialState(entity),
        behavior.commandHandler(entity),
        behavior.eventProjector(entity),
        2.minutes
      )

      val shardRegionRef = ClusterSharding(actorSystem).start(
        typeName = entityName.value,
        entityProps = props,
        settings = ClusterShardingSettings(actorSystem).withRememberEntities(false),
        extractEntityId = extractEntityId,
        extractShardId = extractShardId
      )

      new EntityRef[Entity] {
        override private[aecor] val actorRef: ActorRef = shardRegionRef

        override def handle[C](id: String, command: C)(implicit ec: ExecutionContext, timeout: Timeout, contract: CommandContract[Entity, C]): Future[Result[contract.Rejection]] =
          (actorRef ? Message(MessageId(id), command, NotUsed)).mapTo[EntityResponse[contract.Rejection, NotUsed]].map(_.result)
      }
    }
  }
  def start[Entity](entity: Entity): StartRegion[Entity] = new StartRegion(entity)
}
