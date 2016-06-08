package aecor.core.process

import java.util.UUID

import aecor.core.message.{Correlation, ExtractShardId, Message}
import aecor.core.process.ProcessActor.ProcessBehavior
import aecor.core.serialization.DomainEventSerialization
import akka.Done
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.kafka.ConsumerSettings
import akka.kafka.scaladsl.Consumer
import akka.stream.scaladsl.{Keep, Sink}
import org.apache.kafka.common.serialization.StringDeserializer

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
      extractEntityId = {
        case m @ Message(_, c: Input, _) ⇒ (correlation(c), m)
      },
      extractShardId = {
        case m @ Message(_, c: Input, _) => ExtractShardId(correlation(c), numberOfShards)
      }
    )
  }
}

object Process {
  trait StartProcess[Input] {
    def apply[Schema]
    (actorSystem: ActorSystem, kafkaServers: Set[String], name: String, schema: Schema, behavior: ProcessBehavior[Input], correlation: Correlation[Input], idleTimeout: FiniteDuration)
    (implicit composeConfig: ComposeConfig.Aux[Schema, Input], Input: ClassTag[Input]) = {
      val domainEventSerialization = new DomainEventSerialization
      val processRegion = ProcessActorRegion.start(
        actorSystem,
        name,
        correlation,
        behavior,
        idleTimeout = idleTimeout,
        numberOfShards = 100
      )

      val processStreamConfig = composeConfig(schema)

      val adapterProps = ProcessEventAdapter.props(processRegion) { (topic, event) =>
        processStreamConfig.get(topic).flatMap { f =>
          f(event.payload.toByteArray)
        }
      }

      val counterProcessEventAdapter = actorSystem.actorOf(adapterProps)

      val sink = Sink.actorRefWithAck(
        ref = counterProcessEventAdapter,
        onInitMessage = ProcessEventAdapter.Init,
        ackMessage = ProcessEventAdapter.Forwarded,
        onCompleteMessage = Done
      )

      val consumerSettings = ConsumerSettings(actorSystem, new StringDeserializer, domainEventSerialization, processStreamConfig.keySet)
        .withBootstrapServers(kafkaServers.mkString(","))
        .withGroupId(name)
        .withClientId(s"$name-${UUID.randomUUID()}")
        .withProperty("auto.offset.reset", "earliest")

      Consumer.committableSource(consumerSettings)
        .map(ProcessEventAdapter.Forward)
        .toMat(sink)(Keep.left)
    }
  }

  def startProcess[Input] = new StartProcess[Input] {}
}