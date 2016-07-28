package aecor.core.aggregate

import scala.concurrent.Future

trait AggregateRef[Entity] {
  def handle[Command](commandId: String, command: Command)(implicit contract: CommandContract[Entity, Command]): Future[Result[contract.Rejection]]
}