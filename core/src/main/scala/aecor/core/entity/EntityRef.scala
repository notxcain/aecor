package aecor.core.entity

import aecor.core.message.{Message, MessageId}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern._
import akka.util.Timeout

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

abstract class EntityRef[Entity] {
  private [aecor] def actorRef: ActorRef
  def handle[Command](id: String, command: Command)(implicit ec: ExecutionContext, timeout: Timeout, contract: CommandContract[Entity, Command]): Future[Result[contract.Rejection]]
}

class RemoteEntityRef[C: ClassTag, R](handler: (MessageId, C) => Future[Result[R]])(implicit actorSystem: ActorSystem) {
    private [aecor] val actorRef = actorSystem.actorOf(RemoteEntityRefActor.props(handler))
}

object RemoteEntityRefActor {
  def props[C: ClassTag, R](handler: (MessageId, C) => Future[Result[R]]): Props = Props(new RemoteEntityRefActor(handler))
}

class RemoteEntityRefActor[C: ClassTag, R](handler: (MessageId, C) => Future[Result[R]]) extends Actor {
  import context.dispatcher
  override def receive: Receive = {
    case Message(id, c: C, ack) =>
      handler(id, c).map(result => EntityResponse(ack, result)).pipeTo(sender())
      ()
  }
}
