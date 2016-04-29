package aecor.example.domain

import aecor.example.domain.Account.{CancelHold, HoldPlaced, PlaceHold}
import aecor.example.domain.CardAuthorization._
import aecor.core.entity.EntityRef
import aecor.core.message.Correlation
import aecor.core.process._
import aecor.util.FunctionBuilder
import shapeless._

object CardAuthorizationProcess extends ProcessSyntax {
  type Input = CardAuthorizationCreated :+: HoldPlaced :+: CNil

  implicit val correlation: Correlation[Input] = Correlation.instance(
    FunctionBuilder[Input] { syntax =>
      at[CardAuthorizationCreated](_.transactionId) ::
      at[HoldPlaced](_.transactionId) ::
      HNil
    }.andThen(_.value)
  )

  def behavior(accounts: EntityRef[Account], cardAuthorizations: EntityRef[CardAuthorization]) = {

    def acceptAuthorization(cardAuthorizationId: CardAuthorizationId, accountId: AccountId, transactionId: TransactionId) =
      cardAuthorizations.deliver(AcceptCardAuthorization(cardAuthorizationId)).handlingRejection {
        case AlreadyDeclined => accounts.deliver(CancelHold(accountId, transactionId)).ignoringRejection
        case DoesNotExists => accounts.deliver(CancelHold(accountId, transactionId)).ignoringRejection
        case AlreadyAccepted => doNothing
      }

    def declineAuthorization(cardAuthorizationId: CardAuthorizationId, reason: DeclineReason) =
      cardAuthorizations.deliver(DeclineCardAuthorization(cardAuthorizationId, reason)).ignoringRejection

    FunctionBuilder[Input] { _ =>
      when[CardAuthorizationCreated] { case CardAuthorizationCreated(cardAuthorizationId, accountId, amount, _, _, transactionId) =>
        accounts.deliver(PlaceHold(accountId, transactionId, amount)).handlingRejection {
          case Account.AccountDoesNotExist =>
            declineAuthorization(cardAuthorizationId, CardAuthorization.AccountDoesNotExist)
          case Account.InsufficientFunds =>
            declineAuthorization(cardAuthorizationId, CardAuthorization.InsufficientFunds)
          case other =>
            doNothing
        } and
        become {
          when[CardAuthorizationCreated](ignore) ::
          when[HoldPlaced] { _ =>
            acceptAuthorization(cardAuthorizationId, accountId, transactionId)
          } ::
          HNil
        }
      } ::
      when[HoldPlaced] { case HoldPlaced(accountId, transactionId, amount) =>
        become {
          when[CardAuthorizationCreated] { e =>
            acceptAuthorization(e.cardAuthorizationId, accountId, transactionId)
          } ::
          when[HoldPlaced](ignore) ::
          HNil
        }
      } ::
      HNil
    }
  }
}