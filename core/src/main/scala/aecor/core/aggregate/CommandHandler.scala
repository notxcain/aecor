package aecor.core.aggregate

import aecor.core.aggregate.CommandHandlerResult.{Defer, Now}

import scala.concurrent.{ExecutionContext, Future}
import scala.collection.immutable.Seq

sealed trait AggregateDecision[+R, +E]
case class Accept[+E](events: Seq[E]) extends AggregateDecision[Nothing, E]
case class Reject[+R](rejection: R) extends AggregateDecision[R, Nothing]

sealed trait CommandHandlerResult[+A] {
  def map[B](f: A => B): CommandHandlerResult[B] = this match {
    case Now(value) => Now(f(value))
    case Defer(run) => Defer { implicit ec =>
      run(ec).map(f)
    }
  }
}

object CommandHandlerResult {
  case class Now[+A](value: A) extends CommandHandlerResult[A]
  case class Defer[+A](run: ExecutionContext => Future[A]) extends CommandHandlerResult[A]

  def accept[R, E](events: E*): AggregateDecision[R, E] = Accept(events.toVector)
  def reject[R, E](rejection: R): AggregateDecision[R, E] = Reject(rejection)
  def defer[R, E](f: ExecutionContext => Future[AggregateDecision[R, E]]): CommandHandlerResult[AggregateDecision[R, E]] = Defer(f)

  implicit def fromDecision[R, E](decision: AggregateDecision[R, E]): CommandHandlerResult[AggregateDecision[R, E]] =
    Now(decision)
}

trait CommandHandler[-State, -Command, +Event, +Rejection] {
  def apply(state: State, command: Command): CommandHandlerResult[AggregateDecision[Rejection, Event]]
}

object CommandHandler {
  def apply[State, Command, Event, Rejection](f: State => Command => CommandHandlerResult[AggregateDecision[Rejection, Event]]): CommandHandler[State, Command, Event, Rejection] =
    new CommandHandler[State, Command, Event, Rejection] {
      override def apply(state: State, command: Command): CommandHandlerResult[AggregateDecision[Rejection, Event]] = f(state)(command)
    }
}
