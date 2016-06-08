package aecor.core.process

import aecor.core.message.{Message, MessageId}
import aecor.core.process.ProcessEventAdapter.{Ack, Forward, Forwarded, Init}
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.kafka.ConsumerMessage.{Committable, CommittableMessage, PartitionOffset}
import io.aecor.message.protobuf.Messages.DomainEvent

trait Identifier[A] {
  def apply(a: A): String
}

object ProcessEventAdapter {
  case class Forward(value: CommittableMessage[String, (String, DomainEvent)])
  case object Forwarded
  case object Init
  private case class Ack(c: PartitionOffset)
  def props[T](recipient: ActorRef)(f: (String, DomainEvent) => Option[T]): Props = Props(new ProcessEventAdapter(recipient, f))
}

class ProcessEventAdapter[T](recipient: ActorRef, f: (String, DomainEvent) => Option[T]) extends Actor with ActorLogging {
  import context.dispatcher
  val map = scala.collection.mutable.Map.empty[PartitionOffset, Committable]
  override def receive: Receive = {
    case Init =>
      sender ! Forwarded
    case Forward(cm @ CommittableMessage(key, (topic, value), offset)) =>
      f(topic, value) match {
        case Some(t) =>
          log.debug("Forwarding message [{}] with offset [{}]", t, offset)
          recipient ! Message(MessageId(value.id), t, Ack(cm.partitionOffset))
        case None =>
          log.debug("Ignoring message [{}] with offset [{}]", value, offset)
          offset.commitScaladsl.onComplete { commitResult =>
            log.debug("Commit result [{}]", commitResult)
          }
      }
      sender ! Forwarded
    case Ack(offset) =>
      map.get(offset).foreach { c =>
        c.commitScaladsl().onComplete { commitResult =>
          log.debug("Commit result [{}]", commitResult)
        }
        map.remove(offset)
      }
    case other =>
      log.warning("Unexpected message [{}]", other)
  }
}
