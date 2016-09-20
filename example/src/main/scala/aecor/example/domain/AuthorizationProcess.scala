package aecor.example.domain

import aecor.core.aggregate.AggregateRegionRef
import aecor.core.message.Correlation
import aecor.example.domain.Account.{AuthorizeTransaction, VoidTransaction}
import aecor.example.domain.AuthorizationProcess.{State, _}
import aecor.example.domain.CardAuthorization.{AcceptCardAuthorization, AlreadyAccepted, AlreadyDeclined, CardAuthorizationCreated, DeclineCardAuthorization, DoesNotExists}
import aecor.util.function._
import cats.data.Xor

import scala.concurrent.{ExecutionContext, Future}

class AuthorizationProcess(accounts: AggregateRegionRef[Account.Command], cardAuthorizations: AggregateRegionRef[CardAuthorization.Command])(implicit ec: ExecutionContext) {
  def handleEvent(state: State, input: Input): Future[Option[State]] =
    handle(state, input) {
      case Initial => {
        case CardAuthorizationCreated(cardAuthorizationId, accountId, amount, _, _, transactionId) =>
          accounts.ask(AuthorizeTransaction(accountId, transactionId, amount)).flatMap {
            case Xor.Right(_) =>
              cardAuthorizations.ask(AcceptCardAuthorization(cardAuthorizationId))
              .flatMap {
                case Xor.Left(rejection) => rejection match {
                  case AlreadyDeclined => accounts.ask(VoidTransaction(accountId, transactionId)).map(_ => Some(Finished))
                  case DoesNotExists => accounts.ask(VoidTransaction(accountId, transactionId)).map(_ => Some(Finished))
                  case AlreadyAccepted => Future.successful(Some(Finished))
                }
                case _ =>
                  Future.successful(Some(Finished))
              }
            case Xor.Left(rejection) =>
              rejection match {
                case Account.AccountDoesNotExist =>
                  cardAuthorizations.ask(DeclineCardAuthorization(cardAuthorizationId, CardAuthorization.AccountDoesNotExist)).map(_ => Some(state))
                case Account.InsufficientFunds =>
                  cardAuthorizations.ask(DeclineCardAuthorization(cardAuthorizationId, CardAuthorization.InsufficientFunds)).map(_ => Some(state))
                case Account.DuplicateTransaction =>
                  cardAuthorizations.ask(AcceptCardAuthorization(cardAuthorizationId))
                  .flatMap {
                    case Xor.Left(rejection) => rejection match {
                      case AlreadyDeclined => accounts.ask(VoidTransaction(accountId, transactionId)).map(_ => Some(Finished))
                      case DoesNotExists => accounts.ask(VoidTransaction(accountId, transactionId)).map(_ => Some(Finished))
                      case AlreadyAccepted => Future.successful(Some(Finished))
                    }
                    case _ => Future.successful(Some(Finished))
                  }
              }
          }
      }
      case Finished => _ =>
        Future.successful(None)
    }
}

object AuthorizationProcess {
  val name: String = "CardAuthorizationProcess"

  type Input = CardAuthorizationCreated

  sealed trait State

  case object Initial extends State

  case object Finished extends State

  val correlation: Correlation[Input] = Correlation.instance(_.transactionId.value)

}