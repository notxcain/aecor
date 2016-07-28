package aecor.core.process

import java.util.UUID

import aecor.core.actor.EventsourcedActor
import aecor.core.aggregate.EventId
import aecor.core.message._
import aecor.core.process.ProcessSharding.{Control, TopicName}
import aecor.core.serialization.kafka.PureDeserializer
import aecor.core.serialization.protobuf.EventEnvelope
import aecor.core.streaming.{CommittableMessage, ComposeConfig}
import akka.Done
import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props, Terminated}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.kafka.ConsumerMessage.Committable
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerMessage, ConsumerSettings, Subscriptions}
import akka.pattern.{after, ask}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.Timeout
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag


object ProcessSharding {

  type TopicName = String

  trait Control {
    def shutdown(implicit timeout: Timeout): Future[Done]
  }

  case class Message[+Event, +PassThrough](handleEvent: HandleEvent[Event], passThrough: PassThrough)

  def apply(actorSystem: ActorSystem): ProcessSharding = new ProcessSharding(actorSystem)
}

case class ProcessInputEnvelope[+Input](eventId: EventId, input: Input)

class ProcessInputDeserializer[Input](config: Map[TopicName, (Array[Byte] => Option[Input])]) extends PureDeserializer[Option[ProcessInputEnvelope[Input]]] {
  override def deserialize(topic: TopicName, data: Array[Byte]): Option[ProcessInputEnvelope[Input]] = {
    val envelope = EventEnvelope.parseFrom(data)
    config.get(topic).flatMap(f => f(envelope.event.toByteArray)).map { input =>
      ProcessInputEnvelope(EventId(envelope.id), input)
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

  def flow[Behavior, Input, State, PassThrough]
  (name: String, behavior: Behavior, correlation: Correlation[Input])
  (implicit Input: ClassTag[Input], ec: ExecutionContext, Behavior: ProcessBehavior.Aux[Behavior, Input, State]): Flow[ProcessSharding.Message[Input, PassThrough], PassThrough, ProcessSharding.Control] = {
    val settings = new ProcessShardingSettings(actorSystem.settings.config.getConfig("aecor.process"))

    val processRegion = ClusterSharding(actorSystem).start(
      typeName = name,
      entityProps = EventsourcedActor.props(ProcessEventsourcedBehavior(behavior, Set.empty))(name, settings.idleTimeout(name)),
      settings = ClusterShardingSettings(actorSystem),
      extractEntityId = EventsourcedActor.extractEntityId[HandleEvent[Input]](x => correlation(x.event)),
      extractShardId = EventsourcedActor.extractShardId[HandleEvent[Input]](settings.numberOfShards)(x => correlation(x.event))
    )

    def createControl: Control = new Control {
      override def shutdown(implicit timeout: Timeout): Future[Done] = {
        val actor = actorSystem.actorOf(ShardRegionGracefulShutdownActor.props(processRegion))
        (actor ? ShardRegion.GracefulShutdown).mapTo[Done]
      }
    }

    implicit val askTimeout = Timeout(settings.deliveryTimeout)

    val sendMessage = { m: HandleEvent[Input] => (processRegion ? m).mapTo[EventHandled] }

    val deliverMessage = { m: ProcessSharding.Message[Input, PassThrough] =>
      def run: Future[EventHandled] = sendMessage(m.handleEvent).recoverWith {
        case e =>
          logger.error(s"Event delivery error [$e], scheduling redelivery after [{}]", settings.eventRedeliveryInterval)
          after(settings.eventRedeliveryInterval, actorSystem.scheduler)(run)
      }
      run.map(_ => m.passThrough)
    }

    Flow[ProcessSharding.Message[Input, PassThrough]]
    .mapAsync(settings.parallelism)(deliverMessage)
    .mapMaterializedValue[Control](_ => createControl)
  }

  def committableSink[Behavior, Input, State]
  (name: String, behavior: Behavior, correlation: Correlation[Input])
  (implicit Input: ClassTag[Input], ec: ExecutionContext, Behavior: ProcessBehavior.Aux[Behavior, Input, State]): Sink[ProcessSharding.Message[Input, Committable], ProcessSharding.Control] = {
    val settings = new ProcessShardingSettings(actorSystem.settings.config.getConfig("aecor.process"))
    flow[Behavior, Input, State, Committable](name, behavior, correlation)
    .mapAsync(settings.parallelism)(_.commitScaladsl())
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