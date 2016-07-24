package aecor.core.aggregate

import aecor.core.aggregate.NowOrLater.{Deferred, Now}

import scala.concurrent.{ExecutionContext, Future}
import scala.collection.immutable.Seq

sealed trait AggregateDecision[+R, +E] {
  def map[I](f: E => I): AggregateDecision[R, I] = this match {
    case Accept(events) => Accept(events.map(f))
    case r @ Reject(rejection) => r
  }
}
case class Accept[+E](events: Seq[E]) extends AggregateDecision[Nothing, E]
case class Reject[+R](rejection: R) extends AggregateDecision[R, Nothing]

object AggregateDecision {
  def accept[R, E](events: E*): AggregateDecision[R, E] = Accept(events.toVector)
  def reject[R, E](rejection: R): AggregateDecision[R, E] = Reject(rejection)
  def defer[R, E](f: ExecutionContext => Future[AggregateDecision[R, E]]): NowOrLater[AggregateDecision[R, E]] = Deferred(f)

  implicit def fromDecision[R, E](decision: AggregateDecision[R, E]): NowOrLater[AggregateDecision[R, E]] =
    Now(decision)
}

trait CommandHandler[-State, -Command, +Event, +Rejection] {
  def apply(state: State, command: Command): NowOrLater[AggregateDecision[Rejection, Event]]
}

object CommandHandler {
  def apply[State, Command, Event, Rejection](f: State => Command => NowOrLater[AggregateDecision[Rejection, Event]]): CommandHandler[State, Command, Event, Rejection] =
    new CommandHandler[State, Command, Event, Rejection] {
      override def apply(state: State, command: Command): NowOrLater[AggregateDecision[Rejection, Event]] = f(state)(command)
    }
}
