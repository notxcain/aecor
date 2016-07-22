package aecor.core.streaming

import akka.kafka.ConsumerMessage.Committable


case class CommittableMessage[A](committable: Committable, message: A) {
  def map[B](f: A => B): CommittableMessage[B] = copy(message = f(message))
}