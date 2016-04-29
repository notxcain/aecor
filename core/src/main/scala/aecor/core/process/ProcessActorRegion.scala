package aecor.core.process

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.kafka.scaladsl.Consumer.{CommittableMessage, Control}
import akka.stream.scaladsl.Source
import aecor.core.message.{Correlation, MessageId}
import aecor.core.process.ProcessActor.ProcessBehavior
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

object ProcessActorRegion {
  def start[Input: ClassTag](actorSystem: ActorSystem, name: String, correlation: Correlation[Input], behavior: ProcessBehavior[Input], idleTimeout: FiniteDuration, numberOfShards: Int): ActorRef = {
    val props = Props(new ProcessActor[Input](name, behavior, idleTimeout))
    implicit val c = correlation
    ClusterSharding(actorSystem).start(
      typeName = name,
      entityProps = props,
      settings = ClusterShardingSettings(actorSystem),
      extractEntityId = Correlation.extractEntityId,
      extractShardId = Correlation.extractShardId(numberOfShards)
    )
  }
}

object Process {
  case class PublishedEvent[E](id: MessageId, payload: E)
  def start[Input: Correlation: ClassTag]
  (actorSystem: ActorSystem,
   source: Source[CommittableMessage[String, PublishedEvent[Input]], Control],
   name: String,
   behavior: Input => ProcessAction[Input],
   numberOfShards: Int): Unit = {

  }
}