package aecor.example.domain

import aecor.core.aggregate.{AggregateRegionRef, AggregateResponse}
import aecor.core.message.Correlation
import aecor.core.process.ProcessBehavior
import aecor.core.process.ProcessSyntax._
import aecor.example.domain.Account.{AuthorizeTransaction, TransactionAuthorized, VoidTransaction}
import aecor.example.domain.AuthorizationProcess.{State, _}
import aecor.example.domain.CardAuthorization.{AcceptCardAuthorization, AlreadyAccepted, AlreadyDeclined, CardAuthorizationCreated, DeclineCardAuthorization, DoesNotExists}
import aecor.util.FunctionBuilder
import shapeless._

import scala.concurrent.{ExecutionContext, Future}


class AuthorizationProcess(accounts: AggregateRegionRef[Account.Command], cardAuthorizations: AggregateRegionRef[CardAuthorization.Command])(implicit ec: ExecutionContext) {

  def handleEvent(state: State, input: Input): Future[State] = handleF(state, input) {
    case Initial =>
      when[CardAuthorizationCreated] { case CardAuthorizationCreated(cardAuthorizationId, accountId, amount, _, _, transactionId) =>
        accounts.ask(AuthorizeTransaction(accountId, transactionId, amount)).flatMap {
          case AggregateResponse.Accepted => Future.successful(AwaitingTransactionAuthorized(cardAuthorizationId))
          case AggregateResponse.Rejected(rejection) =>
            val reason = rejection match {
              case Account.AccountDoesNotExist => CardAuthorization.AccountDoesNotExist
              case Account.InsufficientFunds => CardAuthorization.InsufficientFunds
              case other => CardAuthorization.Unknown
            }
            cardAuthorizations.ask(DeclineCardAuthorization(cardAuthorizationId, reason)).ignoreRejection(state)
        }
      } ::
      when[TransactionAuthorized] { case TransactionAuthorized(accountId, transactionId, amount) =>
        Future.successful(AwaitingAuthorizationCreated)
      } ::
      HNil
    case AwaitingTransactionAuthorized(cardAuthorizationId) =>
      when[CardAuthorizationCreated] { e =>
        Future.successful(AwaitingTransactionAuthorized(e.cardAuthorizationId))
      } ::
      when[TransactionAuthorized] { case TransactionAuthorized(accountId, transactionId, amount) =>
        acceptAuthorization(cardAuthorizationId, accountId, transactionId)
      } ::
      HNil
    case AwaitingAuthorizationCreated =>
      when[CardAuthorizationCreated] { case CardAuthorizationCreated(cardAuthorizationId, accountId, amount, _, _, transactionId) =>
        acceptAuthorization(cardAuthorizationId, accountId, transactionId)
      } ::
      when[TransactionAuthorized] { e =>
        Future.successful(Finished)
      } ::
      HNil
    case Finished =>
      when[CardAuthorizationCreated] { _ =>
        Future.successful(Finished)
      } ::
      when[TransactionAuthorized] { _ =>
        Future.successful(Finished)
      } ::
      HNil
  }

  def acceptAuthorization(cardAuthorizationId: CardAuthorizationId, accountId: AccountId, transactionId: TransactionId)(implicit ec: ExecutionContext): Future[State] = {
    cardAuthorizations.ask(AcceptCardAuthorization(cardAuthorizationId)).handleResult(Finished) {
      case AlreadyDeclined => accounts.ask(VoidTransaction(accountId, transactionId)).ignoreRejection(Finished)
      case DoesNotExists => accounts.ask(VoidTransaction(accountId, transactionId)).ignoreRejection(Finished)
      case AlreadyAccepted => Future.successful(Finished)
    }
  }
}

object AuthorizationProcess {
  val name: String = "CardAuthorizationProcess"

  type Input = CardAuthorizationCreated :+: TransactionAuthorized :+: CNil

  sealed trait State
  case object Initial extends State
  case class AwaitingTransactionAuthorized(cardAuthorizationId: CardAuthorizationId) extends State
  case object AwaitingAuthorizationCreated extends State
  case object Finished extends State

  val correlation: Correlation[Input] = Correlation.instance(
    FunctionBuilder[Input] {
      at[CardAuthorizationCreated](_.transactionId) ::
      at[TransactionAuthorized](_.transactionId) ::
      HNil
    }.andThen(_.value)
  )

  implicit def behavior = new ProcessBehavior[AuthorizationProcess] {
    override type Event = AuthorizationProcess.Input
    override type State = AuthorizationProcess.State

    override def init(a: AuthorizationProcess): State = Initial

    override def handleEvent(a: AuthorizationProcess)(state: State, event: Input): Future[State] =
       a.handleEvent(state, event)
  }
}