package aecor.core.process

import java.util.UUID

import aecor.core.aggregate.EventId
import aecor.core.message._
import aecor.core.process.ProcessActor.ProcessBehavior
import aecor.core.process.ProcessSharding.{Control, TopicName}
import aecor.core.serialization.kafka.PureDeserializer
import aecor.core.serialization.protobuf.EventEnvelope
import aecor.core.streaming.CommittableMessage
import akka.Done
import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props, Terminated}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerMessage, ConsumerSettings, Subscriptions}
import akka.pattern.{ask, _}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.Timeout
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory

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

case class ProcessInputEnvelope[Input](eventId: EventId, input: Input)

class ProcessInputDeserializer[Input](config: Map[TopicName, (Array[Byte] => Option[Input])]) extends PureDeserializer[Option[ProcessInputEnvelope[Input]]] {
  override def deserialize(topic: TopicName, data: Array[Byte]): Option[ProcessInputEnvelope[Input]] = {
    val envelope = EventEnvelope.parseFrom(data)
    config.get(topic).flatMap(f => f(envelope.event.toByteArray)).map { input =>
      ProcessInputEnvelope(EventId(s"${envelope.entityId}#${envelope.sequenceNr}"), input)
    }
  }
}

class ProcessSharding(actorSystem: ActorSystem) {
  val logger = LoggerFactory.getLogger(classOf[ProcessSharding])

  def kafkaSource[Schema](kafkaServers: Set[String], schema: Schema, groupId: String)(implicit composeConfig: ComposeConfig[Schema]): Source[CommittableMessage[HandleEvent[composeConfig.Out]], Consumer.Control] = {

    val processStreamConfig = composeConfig(schema)

    val consumerSettings = ConsumerSettings(actorSystem, new StringDeserializer, new ProcessInputDeserializer(processStreamConfig))
      .withBootstrapServers(kafkaServers.mkString(","))
      .withGroupId(groupId)
      .withClientId(s"$groupId-${UUID.randomUUID()}")
      .withProperty("auto.offset.reset", "earliest")

    Consumer.committableSource(consumerSettings, Subscriptions.topics(processStreamConfig.keySet))
      .flatMapConcat {
        case ConsumerMessage.CommittableMessage(_, envelopeOption, offset) =>
          envelopeOption match {
            case Some(envelope) =>
              Source.single(CommittableMessage(offset, HandleEvent(envelope.eventId, envelope.input)))
            case None =>
              Source.fromFuture(offset.commitScaladsl()).flatMapConcat(_ => Source.empty)
          }
      }
  }

  def sink[Input]
  (name: String, behavior: ProcessBehavior[Input], correlation: Correlation[Input], idleTimeout: FiniteDuration)
  (implicit Input: ClassTag[Input], ec: ExecutionContext): Sink[CommittableMessage[HandleEvent[Input]], ProcessSharding.Control] = {
    val settings = new ProcessShardingSettings(actorSystem.settings.config.getConfig("aecor.process"))

    implicit val _correllation = correlation

    val processRegion = ClusterSharding(actorSystem).start(
      typeName = name,
      entityProps = ProcessActor.props[Input](name, behavior, idleTimeout),
      settings = ClusterShardingSettings(actorSystem).withRememberEntities(true),
      extractEntityId = ProcessActor.extractEntityId[Input],
      extractShardId = ProcessActor.extractShardId[Input](settings.numberOfShards)
    )

    def createControl: Control = new Control {
      override def shutdown(implicit timeout: Timeout): Future[Done] = {
        val actor = actorSystem.actorOf(ShardRegionGracefulShutdownActor.props(processRegion))
        (actor ? ShardRegion.GracefulShutdown).mapTo[Done]
      }
    }

    implicit val askTimeout = Timeout(settings.deliveryTimeout)

    val sendMessage = { m: HandleEvent[Input] =>
      (processRegion ? m).mapTo[EventHandled]
    }

    val deliverMessage = { m: CommittableMessage[HandleEvent[Input]] =>
      def run: Future[EventHandled] = sendMessage(m.message).recoverWith {
        case e =>
          logger.error(s"Event delivery error [$e], scheduling redelivery after [{}]", settings.eventRedeliveryInterval)
          after(settings.eventRedeliveryInterval, actorSystem.scheduler)(run)
      }
      run.map(_ => m.committable)
    }

    Flow[CommittableMessage[HandleEvent[Input]]]
      .mapAsync(8)(deliverMessage)
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