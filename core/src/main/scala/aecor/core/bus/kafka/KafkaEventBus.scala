package aecor.core.bus.kafka

import aecor.core.bus.PublishEntityEvent
import aecor.core.entity.EntityEventEnvelope
import aecor.core.serialization.Encoder
import akka.Done
import akka.actor.ActorSystem
import akka.kafka.scaladsl.Producer
import akka.kafka.{ProducerMessage, ProducerSettings}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Materializer, OverflowStrategy}
import akka.util.Timeout
import com.google.protobuf.ByteString
import io.aecor.message.protobuf.Messages.{DomainEvent, Timestamp}
import org.apache.kafka.clients.producer.ProducerRecord

import scala.concurrent.{Future, Promise}

object KafkaEventBus {
  implicit def publisher[Event]: PublishEntityEvent[KafkaEventBus[Event], Event] = new PublishEntityEvent[KafkaEventBus[Event], Event] {
    override def publish(a: KafkaEventBus[Event])(entityName: String, entityId: String, eventEnvelope: EntityEventEnvelope[Event]): Future[Done] =
      a.publish(entityName, entityId, eventEnvelope)
  }
  def apply[Event: Encoder](actorSystem: ActorSystem, producerSettings: ProducerSettings[String, DomainEvent], bufferSize: Int, offerTimeout: Timeout) =
    new KafkaEventBus[Event](actorSystem, producerSettings, bufferSize, offerTimeout)
}

class KafkaEventBus[Event: Encoder](actorSystem: ActorSystem, producerSettings: ProducerSettings[String, DomainEvent], bufferSize: Int, offerTimeout: Timeout) {
  implicit val _offerTimeout = offerTimeout
  implicit val materializer: Materializer = ActorMaterializer(ActorMaterializerSettings(actorSystem), "KafkaEventBus")(actorSystem)

  val queue = Source.queue[ProducerMessage.Message[String, DomainEvent, Promise[Done]]](bufferSize, OverflowStrategy.dropNew)
    .via(Producer.flow(producerSettings))
    .map(x => x.message.passThrough.success(Done))
    .toMat(Sink.ignore)(Keep.left)
    .run

  def publish(entityName: String, entityId: String, eventEnvelope: EntityEventEnvelope[Event]): Future[Done] = {
    val domainEvent = DomainEvent(
      eventEnvelope.id.value,
      ByteString.copyFrom(Encoder[Event].encode(eventEnvelope.event)),
      Timestamp(eventEnvelope.timestamp.toEpochMilli),
      eventEnvelope.causedBy.value
    )
    val record = new ProducerRecord(entityName, entityId, domainEvent)
    val promise = Promise[Done]
    val producerMessage = ProducerMessage.Message(record, promise)
    queue.offer(producerMessage)
    promise.future
  }
}
