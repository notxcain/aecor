package aecor.core

import akka.actor.ActorPath
import akka.persistence.AtLeastOnceDelivery

trait OrderedAtLeastOnceDelivery extends AtLeastOnceDelivery {
  type DeliveryId = Long

  private case class Delivery(destination: ActorPath, deliveryIdToMessage: (DeliveryId) => Any)

  private val deliveryQueue = scala.collection.mutable.Queue.empty[Delivery]

  override def deliver(destination: ActorPath)(deliveryIdToMessage: (DeliveryId) => Any): Unit = {
    if (super.numberOfUnconfirmed == 0) {
      super.deliver(destination)(deliveryIdToMessage)
    } else {
      deliveryQueue.enqueue(Delivery(destination, deliveryIdToMessage))
    }
  }

  override def numberOfUnconfirmed: Int = deliveryQueue.length + super.numberOfUnconfirmed

  override def confirmDelivery(deliveryId: DeliveryId): Boolean = {
    if (deliveryQueue.nonEmpty) {
      val Delivery(destination, deliveryIdToMessage) = deliveryQueue.dequeue()
      super.deliver(destination)(deliveryIdToMessage)
    }
    super.confirmDelivery(deliveryId)
  }
}
