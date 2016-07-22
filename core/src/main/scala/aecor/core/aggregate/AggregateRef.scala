package aecor.core.aggregate

import akka.actor.ActorRef

import scala.concurrent.Future

trait AggregateRef[Entity] {
  private [aecor] def actorRef: ActorRef
  def handle[Command](commandId: String, command: Command)(implicit contract: CommandContract[Entity, Command]): Future[Result[contract.Rejection]]
}