package aecor.core.process

import java.util.UUID

import aecor.core.message.{Correlation, ExtractShardId, Message, MessageId}
import aecor.core.process.ComposeConfig.Aux
import aecor.core.process.ProcessActor.ProcessBehavior
import aecor.core.serialization.DomainEventSerialization
import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props, Terminated}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.http.scaladsl.util.FastFuture
import akka.kafka.ConsumerMessage.CommittableMessage
import akka.kafka.ConsumerSettings
import akka.kafka.scaladsl.Consumer
import akka.pattern.ask
import akka.stream.scaladsl.{Keep, RunnableGraph, Sink}
import akka.util.Timeout
import akka.{Done, NotUsed}
import io.aecor.message.protobuf.Messages.DomainEvent
import org.apache.kafka.common.serialization.StringDeserializer

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

object Process {
  trait Control {
    def stop(): Future[Done]
    def shutdown(implicit timeout: Timeout): Future[Done]
  }
  trait StartProcess[Input] {
    def apply[Schema]
    (actorSystem: ActorSystem, kafkaServers: Set[String], name: String, schema: Schema, behavior: ProcessBehavior[Input], correlation: Correlation[Input], idleTimeout: FiniteDuration, numberOfShards: Int)
    (implicit composeConfig: ComposeConfig.Aux[Schema, Input], Input: ClassTag[Input], ec: ExecutionContext): RunnableGraph[Control]
  }

  def startProcess[Input]: StartProcess[Input] = new StartProcess[Input] {
    override def apply[Schema]
    (actorSystem: ActorSystem, kafkaServers: Set[String], name: String, schema: Schema, behavior: ProcessBehavior[Input], correlation: Correlation[Input], idleTimeout: FiniteDuration, numberOfShards: Int)
    (implicit composeConfig: Aux[Schema, Input], Input: ClassTag[Input], ec: ExecutionContext): RunnableGraph[Control] = {
      val props = ProcessActor.props[Input](name, behavior, idleTimeout)
      val processRegion = ClusterSharding(actorSystem).start(
        typeName = name,
        entityProps = props,
        settings = ClusterShardingSettings(actorSystem).withRememberEntities(true),
        extractEntityId = {
          case m @ Message(_, c: Input, _) ⇒ (correlation(c), m)
        },
        extractShardId = {
          case m @ Message(_, c: Input, _) => ExtractShardId(correlation(c), numberOfShards)
        }
      )
      val processStreamConfig = composeConfig(schema)

      val domainEventSerialization = new DomainEventSerialization
      val consumerSettings = ConsumerSettings(actorSystem, new StringDeserializer, domainEventSerialization, processStreamConfig.keySet)
        .withBootstrapServers(kafkaServers.mkString(","))
        .withGroupId(name)
        .withClientId(s"$name-${UUID.randomUUID()}")
        .withProperty("auto.offset.reset", "earliest")


      implicit val timeout = Timeout(idleTimeout)

      def filter(topic: String, event: DomainEvent) =
        processStreamConfig.get(topic).flatMap { f =>
          f(event.payload.toByteArray)
        }

      Consumer.committableSource(consumerSettings)
        .mapAsync(8) {
          case CommittableMessage(_, (topic, event), offset) =>
            filter(topic, event) match {
              case Some(input) =>
                (processRegion ? Message(MessageId(event.id), input, NotUsed)).map(_ => offset)
              case None =>
                FastFuture.successful(offset)
            }
        }
        .mapAsync(1)(_.commitScaladsl())
        .mapMaterializedValue[Control] { consumerControl =>
          new Control {
            override def shutdown(implicit timeout: Timeout): Future[Done] = {
              consumerControl.shutdown().flatMap { _ =>
                val actor = actorSystem.actorOf(Props(new GracefulShutdownActor(processRegion)))
                (actor ? ShardRegion.GracefulShutdown).mapTo[Done]
              }
            }
            override def stop(): Future[Done] = consumerControl.stop()
          }
        }
        .toMat(Sink.ignore)(Keep.left)
    }
  }
}

class GracefulShutdownActor(region: ActorRef) extends Actor {
  val system = context.system

  def initial: Receive = {
    case ShardRegion.GracefulShutdown ⇒
      context.watch(region)
      region ! ShardRegion.GracefulShutdown
      context.become(waitingTerminated(sender()))
  }
  def waitingTerminated(replyTo: ActorRef): Receive = {
    case Terminated(`region`) ⇒
      context.unwatch(region)
      replyTo ! Done
      self ! PoisonPill
  }
  def receive = initial
}