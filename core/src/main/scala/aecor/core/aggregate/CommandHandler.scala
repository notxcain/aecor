package aecor.core.aggregate

import aecor.core.concurrent.Deferred

import scala.concurrent.{ExecutionContext, Future}

sealed trait CommandHandlerResult[+R, +E]

object CommandHandlerResult {
  case class Accept[+E](events: Seq[E]) extends CommandHandlerResult[Nothing, E]
  case class Reject[+R](rejection: R) extends CommandHandlerResult[R, Nothing]
  case class Defer[E, R](f: Deferred[CommandHandlerResult[R, E]]) extends CommandHandlerResult[R, E]

  def accept[E, R](events: E*): CommandHandlerResult[R, E] = Accept(events)
  def reject[E, R](rejection: R):CommandHandlerResult[R, E] = Reject(rejection)
  def defer[E, R](f: ExecutionContext => Future[CommandHandlerResult[R, E]]): CommandHandlerResult[R, E] = Defer(Deferred(f))
}

trait CommandHandler[-State, -Command, +Event, +Rejection] {
  def apply(state: State, command: Command): CommandHandlerResult[Rejection, Event]
}

object CommandHandler {
  def apply[State, Command, Event, Rejection](f: State => Command => CommandHandlerResult[Rejection, Event]): CommandHandler[State, Command, Event, Rejection] =
    new CommandHandler[State, Command, Event, Rejection] {
      override def apply(state: State, command: Command): CommandHandlerResult[Rejection, Event] = f(state)(command)
    }
}
