package aecor.core

import akka.actor.ActorPath
import akka.persistence.AtLeastOnceDelivery

trait CallerDeliveryIdAtLeastOnceDelivery { self: AtLeastOnceDelivery =>
  private val anyToDeliveryId = scala.collection.mutable.Map.empty[Any, Long]

  def deliver[T](destination: ActorPath, message: T)(f: T => Any): Unit = {
    deliver(destination) { deliveryId =>
      val deliveryAck = f(message)
      anyToDeliveryId += deliveryAck -> deliveryId
      message
    }
  }

  def confirmDelivery(ack: Any): Boolean = {
    anyToDeliveryId.get(ack) match {
      case Some(deliveryId) =>
        confirmDelivery(deliveryId)
      case None =>
        false
    }
  }
}
