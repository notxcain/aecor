package aecor.core.entity

import aecor.core.entity.EntityActor.{Response, Result}
import aecor.core.message.Message
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern._
import akka.util.Timeout

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

abstract class EntityRef[Entity] {
  private [aecor] def actorRef: ActorRef
  def handle[Command](id: String, command: Command)(implicit ec: ExecutionContext, timeout: Timeout, contract: CommandContract[Entity, Command]): Future[Result[contract.Rejection]]
}

class RemoteEntityRef[C: ClassTag, R](handler: C => Future[Result[R]])(implicit actorSystem: ActorSystem) {
    private [aecor] val actorRef = actorSystem.actorOf(RemoteEntityRefActor.props(handler))
}

object RemoteEntityRefActor {
  def props[C: ClassTag, R](handler: C => Future[Result[R]]): Props = Props(new RemoteEntityRefActor(handler))
}

class RemoteEntityRefActor[C: ClassTag, R](handler: C => Future[Result[R]]) extends Actor {
  import context.dispatcher
  override def receive: Receive = {
    case Message(_, c: C, ack) =>
      handler(c).map(result => Response(ack, result)).pipeTo(sender())
      ()
  }
}
