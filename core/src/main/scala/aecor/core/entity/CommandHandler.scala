package aecor.core.entity

import aecor.core.concurrent.Deferred

sealed trait CommandHandlerResult[+E, +R]

object CommandHandlerResult {
  case class Accept[+E](event: E) extends CommandHandlerResult[E, Nothing]
  case class Reject[+R](rejection: R) extends CommandHandlerResult[Nothing, R]
  case class Defer[E, R](f: Deferred[CommandHandlerResult[E, R]]) extends CommandHandlerResult[E, R]

  def accept[E, R](event: E): CommandHandlerResult[E, R] = Accept(event)
  def reject[E, R](rejection: R):CommandHandlerResult[E, R] = Reject(rejection)
  def defer[E, R](f: Deferred[CommandHandlerResult[E, R]]): CommandHandlerResult[E, R] = Defer(f)
}

trait CommandHandler[-State, -Command, +Event, +Rejection] {
  def apply(state: State, command: Command): CommandHandlerResult[Event, Rejection]
}

object CommandHandler {
  def instance[State, Command, Event, Rejection](f: State => Command => CommandHandlerResult[Event, Rejection]): CommandHandler[State, Command, Event, Rejection] =
    new CommandHandler[State, Command, Event, Rejection] {
      override def apply(state: State, command: Command): CommandHandlerResult[Event, Rejection] = f(state)(command)
    }
}
