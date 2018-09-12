package aecor.example.domain.account

import aecor.data.Folded
import aecor.data.Folded.syntax._
import aecor.data.next.{EventsourcedBehavior, MonadAction}
import aecor.example.domain.Amount
import aecor.example.domain.account.Account.{AccountDoesNotExist, InsufficientFunds}
import aecor.example.domain.account.AccountEvent._
import aecor.example.domain.account.EventsourcedAccount.AccountState
import cats.Monad
import cats.implicits._

final class EventsourcedAccount[F[_]](implicit F: MonadAction[F, Option[AccountState], AccountEvent, Account.Rejection]) extends Account[F] {

  import F._

  override def open(
    checkBalance: Boolean
  ): F[Unit] =
    read.flatMap {
      case None =>
        append(AccountOpened(checkBalance))
      case Some(_) =>
        reject(Account.AccountExists)
    }

  override def credit(
    transactionId: AccountTransactionId,
    amount: Amount
  ): F[Unit] =
    read.flatMap {
      case Some(account) =>
        if (account.processedTransactions.contains(transactionId)) {
          ().pure[F]
        } else {
          append(AccountCredited(transactionId, amount))
        }
      case None =>
        reject(Account.AccountDoesNotExist)
    }

  override def debit(
    transactionId: AccountTransactionId,
    amount: Amount
  ): F[Unit] =
    read.flatMap {
      case Some(account) =>
        if (account.processedTransactions.contains(transactionId)) {
          ().pure[F]
        } else {
          if (account.hasFunds(amount)) {
            append(AccountDebited(transactionId, amount))
          } else {
            reject(InsufficientFunds)
          }
        }
      case None =>
        reject(AccountDoesNotExist)
    }
}

object EventsourcedAccount {

  def behavior[F[_]: Monad]: EventsourcedBehavior[Account, F, Option[AccountState], AccountEvent, Account.Rejection] =
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
