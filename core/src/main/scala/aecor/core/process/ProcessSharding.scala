package aecor.core.process

import java.util.UUID

import aecor.core.message._
import aecor.core.process.ProcessActor.ProcessBehavior
import aecor.core.process.ProcessSharding.{Control, TopicName}
import aecor.core.serialization.PureDeserializer
import aecor.core.serialization.protobuf.ExternalEntityEventEnvelope
import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props, Terminated}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.kafka.ConsumerMessage.{Committable, CommittableMessage}
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.pattern.ask
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.Timeout
import akka.{Done, NotUsed}
import com.typesafe.config.ConfigFactory
import org.apache.kafka.common.serialization.StringDeserializer

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag


object ProcessSharding {

  type TopicName = String

  trait Control {
    def shutdown(implicit timeout: Timeout): Future[Done]
  }

  def apply(actorSystem: ActorSystem): ProcessSharding = new ProcessSharding(actorSystem)
}

case class ProcessInputEnvelope[Input](eventId: MessageId, input: Input)

class ProcessInputDeserializer[Input](config: Map[TopicName, (Array[Byte] => Option[Input])]) extends PureDeserializer[Option[ProcessInputEnvelope[Input]]] {
  override def deserialize(topic: TopicName, data: Array[Byte]): Option[ProcessInputEnvelope[Input]] = {
    val envelope = ExternalEntityEventEnvelope.parseFrom(data)
    config.get(topic).flatMap(f => f(envelope.event.toByteArray)).map { input =>
      ProcessInputEnvelope(MessageId(s"${envelope.entityId}#${envelope.sequenceNr}"), input)
    }
  }
}

class ProcessSharding(actorSystem: ActorSystem) {
  def source[Schema](kafkaServers: Set[String], schema: Schema, groupId: String)(implicit composeConfig: ComposeConfig[Schema]): Source[Message[composeConfig.Out, Committable], Consumer.Control] = {
    val processStreamConfig = composeConfig(schema)

    val consumerSettings = ConsumerSettings(actorSystem, new StringDeserializer, new ProcessInputDeserializer(processStreamConfig))
      .withBootstrapServers(kafkaServers.mkString(","))
      .withGroupId(groupId)
      .withClientId(s"$groupId-${UUID.randomUUID()}")
      .withProperty("auto.offset.reset", "earliest")

    Consumer.committableSource(consumerSettings, Subscriptions.topics(processStreamConfig.keySet))
      .flatMapConcat {
        case CommittableMessage(_, envelopeOption, offset) =>
          envelopeOption match {
            case Some(envelope) =>
              Source.single(Message(envelope.eventId, envelope.input, offset))
            case None =>
              Source.fromFuture(offset.commitScaladsl()).flatMapConcat(_ => Source.empty)
          }
      }
  }

  def sink[Input]
  (name: String, behavior: ProcessBehavior[Input], correlation: Correlation[Input], idleTimeout: FiniteDuration)
  (implicit Input: ClassTag[Input], ec: ExecutionContext): Sink[Message[Input, Committable], ProcessSharding.Control] = {
    val config = ConfigFactory.load()
    val numberOfShards = config.getInt("aecor.process.number-of-shards")
    val props = ProcessActor.props[Input](name, behavior, idleTimeout)
    val processRegion = ClusterSharding(actorSystem).start(
      typeName = name,
      entityProps = props,
      settings = ClusterShardingSettings(actorSystem).withRememberEntities(true),
      extractEntityId = {
        case m @ Message(_, input: Input, _) => (correlation(input), m)
      },
      extractShardId = {
        case m @ Message(_, input: Input, _) => ExtractShardId(correlation(input), numberOfShards)
      }
    )

    def createControl: Control = new Control {
      override def shutdown(implicit timeout: Timeout): Future[Done] = {
        val actor = actorSystem.actorOf(ShardRegionGracefulShutdownActor.props(processRegion))
        (actor ? ShardRegion.GracefulShutdown).mapTo[Done]
      }
    }

    implicit val askTimeout = Timeout(idleTimeout)

    Flow[Message[Input, Committable]]
      .mapAsync(8) { m =>
        (processRegion ? m.copy(ack = NotUsed)).map(_ => m.ack)
      }
      .mapAsync(1)(_.commitScaladsl())
      .mapMaterializedValue[Control](_ => createControl)
      .toMat(Sink.ignore)(Keep.left)
  }
}


object ShardRegionGracefulShutdownActor {
  def props(shardRegionRef: ActorRef): Props = Props(new ShardRegionGracefulShutdownActor(shardRegionRef))
}


class ShardRegionGracefulShutdownActor(region: ActorRef) extends Actor {
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