package aecor.example.domain

import aecor.core.actor.NowOrDeferred
import aecor.core.actor.NowOrDeferred.Deferred
import aecor.core.aggregate.{AggregateRef, Result}
import aecor.core.message.Correlation
import aecor.core.process.ProcessBehavior
import aecor.core.process.ProcessSyntax._
import aecor.example.domain.Account.{AuthorizeTransaction, TransactionAuthorized, VoidTransaction}
import aecor.example.domain.AuthorizationProcess.{State, _}
import aecor.example.domain.CardAuthorization.{AcceptCardAuthorization, AlreadyAccepted, AlreadyDeclined, CardAuthorizationCreated, DeclineCardAuthorization, DoesNotExists}
import aecor.util.FunctionBuilder
import shapeless._

import scala.concurrent.{ExecutionContext, Future}


case class AuthorizationProcess(accounts: AggregateRef[Account], cardAuthorizations: AggregateRef[CardAuthorization], state: State) {

  def handleEvent(input: Input)(implicit ec: ExecutionContext): Future[State] = handleF(state, input) {
    case Initial =>
      when[CardAuthorizationCreated] { case CardAuthorizationCreated(cardAuthorizationId, accountId, amount, _, _, transactionId) =>
        accounts.handle(s"Authorize-$transactionId", AuthorizeTransaction(accountId, transactionId, amount)).flatMap {
          case Result.Accepted => Future.successful(AwaitingTransactionAuthorized(cardAuthorizationId))
          case Result.Rejected(rejection) =>
            val reason = rejection match {
              case Account.AccountDoesNotExist => CardAuthorization.AccountDoesNotExist
              case Account.InsufficientFunds => CardAuthorization.InsufficientFunds
              case other => CardAuthorization.Unknown
            }
            cardAuthorizations.handle(s"Decline-$cardAuthorizationId", DeclineCardAuthorization(cardAuthorizationId, reason)).ignoringRejection(state)
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
    cardAuthorizations.handle(s"Accept", AcceptCardAuthorization(cardAuthorizationId)).handlingRejection(Finished) {
      case AlreadyDeclined => accounts.handle(s"Void-$transactionId", VoidTransaction(accountId, transactionId)).ignoringRejection(Finished)
      case DoesNotExists => accounts.handle(s"Void-$transactionId", VoidTransaction(accountId, transactionId)).ignoringRejection(Finished)
      case AlreadyAccepted => Future.successful(Finished)
    }
  }

  def withState(state: State) = copy(state = state)
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

    override def withState(a: AuthorizationProcess)(state: State): AuthorizationProcess = a.copy(state = state)


    override def handleEvent(a: AuthorizationProcess)(event: Event): NowOrDeferred[State] = Deferred(ec => a.handleEvent(event)(ec))
  }
}