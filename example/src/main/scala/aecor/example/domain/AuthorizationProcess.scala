package aecor.example.domain

import aecor.example.domain.AccountAggregateOp._
import aecor.example.domain.CardAuthorizationAggregateEvent.CardAuthorizationCreated
import aecor.example.domain.CardAuthorizationAggregateOp._
import akka.stream.scaladsl.Flow
import akka.{ Done, NotUsed }
import cats.free.Free
import freek._

import scala.concurrent.{ ExecutionContext, Future }

object AuthorizationProcess {
  type PRG =
    AccountAggregateOp :|: CardAuthorizationAggregateOp :|: NilDSL

  val PRG = DSL.Make[PRG]

  def pure[A](a: A): Free[PRG.Cop, A] = Free.pure(a)

  def handleEvent: CardAuthorizationCreated => Free[PRG.Cop, Done] = {
    case CardAuthorizationCreated(cardAuthorizationId, accountId, amount, _, _, transactionId) =>
      AuthorizeTransaction(accountId, transactionId, amount).freek[PRG].flatMap {
        case Right(_) =>
          AcceptCardAuthorization(cardAuthorizationId).freek[PRG].flatMap {
            case Left(rejection) =>
              rejection match {
                case AlreadyDeclined =>
                  VoidTransaction(accountId, transactionId).freek[PRG].map(_ => Done)
                case DoesNotExists =>
                  VoidTransaction(accountId, transactionId).freek[PRG].map(_ => Done)
                case AlreadyAccepted => pure(Done)
              }
            case _ =>
              pure(Done)
          }
        case Left(rejection) =>
          rejection match {
            case AccountAggregateOp.AccountDoesNotExist =>
              DeclineCardAuthorization(cardAuthorizationId, "Unknown account")
                .freek[PRG]
                .map(_ => Done)
            case AccountAggregateOp.InsufficientFunds =>
              DeclineCardAuthorization(cardAuthorizationId, "InsufficientFunds")
                .freek[PRG]
                .map(_ => Done)
            case AccountAggregateOp.DuplicateTransaction =>
              AcceptCardAuthorization(cardAuthorizationId).freek[PRG].flatMap {
                case Left(r) =>
                  r match {
                    case AlreadyDeclined =>
                      VoidTransaction(accountId, transactionId).freek[PRG].map(_ => Done)
                    case DoesNotExists =>
                      VoidTransaction(accountId, transactionId).freek[PRG].map(_ => Done)
                    case AlreadyAccepted => pure(Done)
                  }
                case _ => pure(Done)
              }
          }
      }
  }

  def flow[PassThrough, F2[_] <: CopK[_]](parallelism: Int, interpreter: Interpreter[F2, Future])(
    implicit sub: SubCop[PRG.Cop, F2],
    ec: ExecutionContext
  ): Flow[(CardAuthorizationCreated, PassThrough), PassThrough, NotUsed] =
    Flow[(CardAuthorizationCreated, PassThrough)].mapAsync(parallelism) {
      case (e, ps) =>
        import cats.instances.future._
        handleEvent(e).interpret(interpreter).map(_ => ps)
    }
}
