package aecor.core.bus

import aecor.core.entity.EntityEventEnvelope
import akka.Done

import scala.concurrent.Future


class PublishEntityEventOps[A](val self: A) extends AnyVal {
  def publish[Event](entityName: String, entityId: String, eventEnvelope: EntityEventEnvelope[Event])(implicit ev: PublishEntityEvent[A, Event]): Future[Done] =
    ev.publish(self)(entityName, entityId, eventEnvelope)
}

object PublishEntityEvent {
  implicit def toOps[A](self: A): PublishEntityEventOps[A] = new PublishEntityEventOps[A](self)
}

trait PublishEntityEvent[A, Event] {
  def publish(a: A)(entityName: String, entityId: String, eventEnvelope: EntityEventEnvelope[Event]): Future[Done]
}
