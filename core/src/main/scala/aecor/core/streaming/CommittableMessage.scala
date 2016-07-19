package aecor.core.streaming

import akka.kafka.ConsumerMessage.Committable


case class CommittableMessage[A](committable: Committable, message: A)