package aecor.core.entity

import aecor.core.entity.EntityActor.{Response, Result}
import aecor.core.message.{Correlation, ExtractShardId, Message, MessageId}
import aecor.core.serialization.Encoder
import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.pattern._
import akka.util.Timeout

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

object EntityActorRegion {
  class StartRegion[Entity] {
    def apply[State, Command, Event, Rejection]
    (actorSystem: ActorSystem, initialState: State, messageQueue: ActorRef, numberOfShards: Int)
    (implicit
     A: ClassTag[State],
     C: ClassTag[Command],
     E: ClassTag[Event],
     commandContract: CommandContract.Aux[Entity, Command, Rejection],
     commandHandler: CommandHandler.Aux[Entity, State, Command, Event, Rejection],
     encoderE: Encoder[Event],
     eventProjector: EventProjector[Entity, State, Event],
     correlation: Correlation[Command],
     entityName: EntityName[Entity]
    ): EntityRef[Entity] = {
      new EntityRef[Entity] {
        val props = Props(new EntityActor[Entity, State, Command, Event, Rejection](entityName.value, initialState, messageQueue))
        override private[aecor] val actorRef: ActorRef = ClusterSharding(actorSystem).start(
          typeName = entityName.value,
          entityProps = props,
          settings = ClusterShardingSettings(actorSystem),
          extractEntityId = extractEntityId,
          extractShardId = extractShardId
        )

        def extractEntityId: ShardRegion.ExtractEntityId = {
          case m @ Message(_, c: Command, _) ⇒ (correlation(c), m)
          case m @ Message(_, c: MarkEventAsPublished, _) => (c.entityId, c)
        }

        def extractShardId: ShardRegion.ExtractShardId = {
          case m @ Message(_, c: Command, _) => ExtractShardId(correlation(c), numberOfShards)
          case m @ Message(_, c: MarkEventAsPublished, _) => ExtractShardId(c.entityId, numberOfShards)
        }

        override def handle[C](id: String, command: C)(implicit ec: ExecutionContext, timeout: Timeout, contract: CommandContract[Entity, C]): Future[Result[contract.Rejection]] =
          (actorRef ? Message(MessageId(id), command, NotUsed)).mapTo[Response[contract.Rejection, NotUsed]].map(_.result)
      }
    }
  }

  def start[Entity]: StartRegion[Entity] = new StartRegion[Entity]
}
