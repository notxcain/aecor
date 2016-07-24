package aecor.example.domain

import aecor.core.aggregate.AggregateRef
import aecor.core.message.Correlation
import aecor.core.process.ProcessSyntax._
import aecor.example.domain.Account.{AuthorizeTransaction, TransactionAuthorized, VoidTransaction}
import aecor.example.domain.CardAuthorization._
import aecor.util.FunctionBuilder
import shapeless._
import scala.concurrent.duration._

import scala.concurrent.duration.FiniteDuration

object AuthorizationProcess {
  val name: String = "CardAuthorizationProcess"

  val idleTimeout: FiniteDuration = 20.seconds
  type Input = CardAuthorizationCreated :+: TransactionAuthorized :+: CNil

  val correlation: Correlation[Input] = Correlation.instance(
    FunctionBuilder[Input] {
      at[CardAuthorizationCreated](_.transactionId) ::
      at[TransactionAuthorized](_.transactionId) ::
      HNil
    }.andThen(_.value)
  )

  def behavior(accounts: AggregateRef[Account], cardAuthorizations: AggregateRef[CardAuthorization]) = {

    def acceptAuthorization(cardAuthorizationId: CardAuthorizationId, accountId: AccountId, transactionId: TransactionId) =
      cardAuthorizations.deliver(AcceptCardAuthorization(cardAuthorizationId)).handlingRejection {
        case AlreadyDeclined => accounts.deliver(VoidTransaction(accountId, transactionId)).ignoringRejection
        case DoesNotExists => accounts.deliver(VoidTransaction(accountId, transactionId)).ignoringRejection
        case AlreadyAccepted => doNothing
      }

    def declineAuthorization(cardAuthorizationId: CardAuthorizationId, reason: DeclineReason) =
      cardAuthorizations.deliver(DeclineCardAuthorization(cardAuthorizationId, reason)).ignoringRejection

    FunctionBuilder[Input] {
      when[CardAuthorizationCreated] { case CardAuthorizationCreated(cardAuthorizationId, accountId, amount, _, _, transactionId) =>
        accounts.deliver(AuthorizeTransaction(accountId, transactionId, amount)).handlingRejection {
          case Account.AccountDoesNotExist =>
            declineAuthorization(cardAuthorizationId, CardAuthorization.AccountDoesNotExist)
          case Account.InsufficientFunds =>
            declineAuthorization(cardAuthorizationId, CardAuthorization.InsufficientFunds)
          case other =>
            doNothing
        } and
        become {
          when[CardAuthorizationCreated](ignore) ::
          when[TransactionAuthorized] { _ =>
            acceptAuthorization(cardAuthorizationId, accountId, transactionId)
          } ::
          HNil
        }
      } ::
      when[TransactionAuthorized] { case TransactionAuthorized(accountId, transactionId, amount) =>
        become {
          when[CardAuthorizationCreated] { e =>
            acceptAuthorization(e.cardAuthorizationId, accountId, transactionId)
          } ::
          when[TransactionAuthorized](ignore) ::
          HNil
        }
      } ::
      HNil
    }
  }
}