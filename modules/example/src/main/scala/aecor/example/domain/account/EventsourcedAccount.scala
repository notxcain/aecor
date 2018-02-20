package aecor.example.domain.account

import aecor.data.Folded.syntax._
import aecor.data._
import aecor.example.domain.Amount
import aecor.example.domain.account.Account.{ AccountDoesNotExist, InsufficientFunds }
import aecor.example.domain.account.AccountEvent._
import aecor.example.domain.account.EventsourcedAccount.AccountState
import cats.implicits._
import scala.collection.immutable._

class EventsourcedAccount extends Account[Action[Option[AccountState], AccountEvent, ?]] {

  override def open(
    checkBalance: Boolean
  ): Action[Option[AccountState], AccountEvent, Either[Account.Rejection, Unit]] =
    Action {
      case None =>
        List(AccountOpened(checkBalance)) -> ().asRight
      case Some(_) =>
        List.empty -> Account.AccountExists.asLeft
    }

  override def credit(
    transactionId: AccountTransactionId,
    amount: Amount
  ): Action[Option[AccountState], AccountEvent, Either[Account.Rejection, Unit]] =
    Action {
      case Some(account) =>
        if (account.processedTransactions.contains(transactionId)) {
          List.empty -> ().asRight
        } else {
          List(AccountCredited(transactionId, amount)) -> ().asRight
        }
      case None =>
        List.empty -> Account.AccountDoesNotExist.asLeft
    }

  override def debit(
    transactionId: AccountTransactionId,
    amount: Amount
  ): Action[Option[AccountState], AccountEvent, Either[Account.Rejection, Unit]] =
    Action {
      case Some(account) =>
        if (account.processedTransactions.contains(transactionId)) {
          List.empty -> ().asRight
        } else {
          if (account.hasFunds(amount)) {
            List(AccountDebited(transactionId, amount)) -> ().asRight
          } else {
            List.empty -> InsufficientFunds.asLeft
          }
        }
      case None =>
        List.empty -> AccountDoesNotExist.asLeft
    }
}

object EventsourcedAccount {

  def behavior: EventsourcedBehavior[Account, Option[AccountState], AccountEvent] =
    EventsourcedBehavior
      .optional(new EventsourcedAccount, AccountState.fromEvent, _.applyEvent(_))

  final val rootAccountId: AccountId = AccountId("ROOT")
  final case class AccountState(balance: Amount,
                                processedTransactions: Set[AccountTransactionId],
                                checkBalance: Boolean) {
    def hasFunds(amount: Amount): Boolean =
      !checkBalance || balance >= amount
    def applyEvent(event: AccountEvent): Folded[AccountState] = event match {
      case AccountOpened(_) => impossible
      case AccountDebited(transactionId, amount) =>
        copy(
          balance = balance - amount,
          processedTransactions = processedTransactions + transactionId
        ).next
      case AccountCredited(transactionId, amount) =>
        copy(
          balance = balance + amount,
          processedTransactions = processedTransactions + transactionId
        ).next
    }
  }
  object AccountState {
    def fromEvent(event: AccountEvent): Folded[AccountState] = event match {
      case AccountOpened(checkBalance) => AccountState(Amount.zero, Set.empty, checkBalance).next
      case _                           => impossible
    }
  }
}
