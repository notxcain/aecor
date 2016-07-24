package aecor.core.aggregate

import aecor.core.aggregate.NowOrLater.{Deferred, Now}

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}


sealed trait AggregateDecision[+R, +E]
case class Accept[+E](events: Seq[E]) extends AggregateDecision[Nothing, E]
case class Reject[+R](rejection: R) extends AggregateDecision[R, Nothing]

object AggregateDecision {

  def accept[R, E](events: E*): AggregateDecision[R, E] = Accept(events.toVector)
  def reject[R, E](rejection: R): AggregateDecision[R, E] = Reject(rejection)
  def defer[R, E](f: ExecutionContext => Future[AggregateDecision[R, E]]): NowOrLater[AggregateDecision[R, E]] = Deferred(f)

  implicit def fromDecision[R, E](decision: AggregateDecision[R, E]): NowOrLater[AggregateDecision[R, E]] =
    Now(decision)
}

object CommandHandler {
  def apply[State, Command, Event, Rejection](state: State, command: Command)(f: State => Command => NowOrLater[AggregateDecision[Rejection, Event]]): NowOrLater[CommandHandlerResult[Result[Rejection], Event]] =
    f(state)(command).map {
      case a: Accept[Event] => CommandHandlerResult(Result.Accepted, a.events)
      case a: Reject[Rejection] => CommandHandlerResult(Result.Rejected(a.rejection), Seq.empty)
    }
}