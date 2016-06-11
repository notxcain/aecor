package aecor.core.bus

import java.time.Instant

import aecor.core.bus.EventBusPublisher.DomainEventEnvelope
import aecor.core.message.MessageId
import akka.Done

import scala.concurrent.Future

object EventBusPublisher {
  case class DomainEventEnvelope[+Event](id: MessageId, event: Event, timestamp: Instant, causedBy: MessageId)
}

trait EventBusPublisher[A, Event] {
  def publish(a: A)(entityName: String, entityId: String, eventEnvelope: DomainEventEnvelope[Event]): Future[Done]
}
