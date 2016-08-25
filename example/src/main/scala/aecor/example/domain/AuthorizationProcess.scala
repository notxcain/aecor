package aecor.example.domain

import aecor.util.function._
import aecor.core.aggregate.{AggregateRegionRef, AggregateResponse}
import aecor.core.message.Correlation
import aecor.core.process.ProcessBehavior
import aecor.core.process.ProcessSyntax._
import aecor.example.domain.Account.{AuthorizeTransaction, VoidTransaction}
import aecor.example.domain.AuthorizationProcess.{State, _}
import aecor.example.domain.CardAuthorization.{AcceptCardAuthorization, AlreadyAccepted, AlreadyDeclined, CardAuthorizationCreated, DeclineCardAuthorization, DoesNotExists}
import cats.std.future._
import scala.concurrent.{ExecutionContext, Future}

class AuthorizationProcess(accounts: AggregateRegionRef[Account.Command], cardAuthorizations: AggregateRegionRef[CardAuthorization.Command])(implicit ec: ExecutionContext) {
  def handleEvent(state: State, input: Input): Future[Option[State]] =
    handle(state, input) {
      case Initial => {
        case CardAuthorizationCreated(cardAuthorizationId, accountId, amount, _, _, transactionId) =>
          accounts.ask(AuthorizeTransaction(accountId, transactionId, amount)).flatMap {
            case AggregateResponse.Accepted =>
              cardAuthorizations.ask(AcceptCardAuthorization(cardAuthorizationId)).handleResult(Some(Finished)) {
                case AlreadyDeclined => accounts.ask(VoidTransaction(accountId, transactionId)).ignoreRejection(Some(Finished))
                case DoesNotExists => accounts.ask(VoidTransaction(accountId, transactionId)).ignoreRejection(Some(Finished))
                case AlreadyAccepted => Future.successful(Some(Finished))
              }
            case AggregateResponse.Rejected(rejection) =>
              rejection match {
                case Account.AccountDoesNotExist =>
                  cardAuthorizations.ask(DeclineCardAuthorization(cardAuthorizationId, CardAuthorization.AccountDoesNotExist)).ignoreRejection(Some(state))
                case Account.InsufficientFunds =>
                  cardAuthorizations.ask(DeclineCardAuthorization(cardAuthorizationId, CardAuthorization.InsufficientFunds)).ignoreRejection(Some(state))
                case Account.DuplicateTransaction =>
                  cardAuthorizations.ask(AcceptCardAuthorization(cardAuthorizationId)).handleResult(Some(Finished)) {
                    case AlreadyDeclined => accounts.ask(VoidTransaction(accountId, transactionId)).ignoreRejection(Some(Finished))
                    case DoesNotExists => accounts.ask(VoidTransaction(accountId, transactionId)).ignoreRejection(Some(Finished))
                    case AlreadyAccepted => Future.successful(Some(Finished))
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


  implicit def behavior = new ProcessBehavior[AuthorizationProcess] {
    override type Event = AuthorizationProcess.Input
    override type State = AuthorizationProcess.State

    override def init: State = Initial

    override def handleEvent(a: AuthorizationProcess)(state: State, event: Input): Future[Option[State]] =
       a.handleEvent(state, event)
  }
}