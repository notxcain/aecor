package aecor.core.entity

import aecor.core.entity.EntityActor.Response
import aecor.core.message.{Correlation, Message}
import aecor.core.serialization.Encoder
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
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
          extractEntityId = Correlation.extractEntityId[Command],
          extractShardId = Correlation.extractShardId[Command](numberOfShards)
        )

        override def ?[C, Ack](c: Message[C, Ack])(implicit ec: ExecutionContext, timeout: Timeout, contract: CommandContract[Entity, C]): Future[Response[contract.Rejection, Ack]] =
          (actorRef ? c).mapTo[Response[contract.Rejection, Ack]]
      }
    }
  }

  def start[Entity]: StartRegion[Entity] = new StartRegion[Entity]
}
