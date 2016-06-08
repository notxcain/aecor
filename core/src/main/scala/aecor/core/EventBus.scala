package aecor.core

import aecor.core.EventBus.PublishMessage
import akka.actor.{Actor, ActorLogging, ActorRef, Stash}
import akka.kafka.{ProducerMessage, ProducerSettings}
import akka.kafka.scaladsl.Producer
import akka.pattern.pipe
import akka.stream.QueueOfferResult.{Dropped, Enqueued, Failure, QueueClosed}
import akka.stream._
import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import io.aecor.message.protobuf.Messages.DomainEvent
import org.apache.kafka.clients.producer.ProducerRecord

object EventBus {
  case class PublishMessage[Reply](topic: String, key: String, message: DomainEvent, replyWith: Reply, replyTo: ActorRef)
}

class EventBus(producerSettings: ProducerSettings[String, DomainEvent], bufferSize: Int) extends Actor with ActorLogging with Stash {
  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(context.system).withSupervisionStrategy(_ => Supervision.Restart))
  import materializer._

  type Queue = SourceQueueWithComplete[ProducerMessage.Message[String, DomainEvent, (ActorRef, Any)]]

  def offering(queue: Queue): Receive = {
    case PublishMessage(topic, key, message, passThrough, replyTo) =>
      val record = new ProducerRecord(topic, key, message)
      val producerMessage = ProducerMessage.Message(record, replyTo -> passThrough)
      queue.offer(producerMessage).pipeTo(self)
      context.become(waitingForOfferResult(producerMessage, queue))
  }
  def waitingForOfferResult(producerMessage: ProducerMessage.Message[String, DomainEvent, (ActorRef, Any)], queue: Queue): Receive = {
    case qor: QueueOfferResult => qor match {
      case Enqueued =>
        unstashAll()
        context.become(offering(queue))
      case Dropped =>
        log.warning("Queue dropped offered message")
        unstashAll()
        context.become(offering(queue))
      case Failure(cause) =>
        log.error(cause, "Offering failed")
        log.info("Recreating queue")
        val newQueue = createQueue
        newQueue.offer(producerMessage).pipeTo(self)
        context.become(waitingForOfferResult(producerMessage, newQueue))
      case QueueClosed =>
        log.info("Queue closed")
        log.info("Recreating queue")
        val newQueue = createQueue
        newQueue.offer(producerMessage).pipeTo(self)
        context.become(waitingForOfferResult(producerMessage, newQueue))
    }
    case _ => stash()
  }

  def createQueue = Source
    .queue[ProducerMessage.Message[String, DomainEvent, (ActorRef, Any)]](bufferSize, OverflowStrategy.dropNew)
    .viaMat(Producer.flow(producerSettings))(Keep.left)
    .map(_.message.passThrough)
    .to(Sink.foreach {
      case (replyTo, replyWith) => replyTo ! replyWith
    })
    .run


  override def receive: Receive = offering(createQueue)
}
