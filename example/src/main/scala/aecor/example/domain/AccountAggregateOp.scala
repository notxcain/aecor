package aecor.example.domain

import aecor.core.message.Correlation
import akka.Done
import cats.free.Free

object AccountAggregateOp {
  implicit def correlation: Correlation[AccountAggregateOp[_]] =
    Correlation.instance(_.accountId.value)

  type AccountAggregateOpF[A] = Free[AccountAggregateOp, A]

  def openAccount(
      accountId: AccountId): AccountAggregateOpF[Rejection Either Done] =
    Free.liftF(OpenAccount(accountId))

  def authorizeTransaction(accountId: AccountId,
                           transactionId: TransactionId,
                           amount: Amount)
    : AccountAggregateOpF[AuthorizeTransactionRejection Either Done] =
    Free.liftF(AuthorizeTransaction(accountId, transactionId, amount))

  def voidTransaction(accountId: AccountId, transactionId: TransactionId)
    : AccountAggregateOpF[Rejection Either Done] =
    Free.liftF(VoidTransaction(accountId, transactionId))

  def captureTransaction(
      accountId: AccountId,
      transactionId: TransactionId,
      amount: Amount): AccountAggregateOpF[Rejection Either Done] =
    Free.liftF(CaptureTransaction(accountId, transactionId, amount))

  def creditAccount(
      accountId: AccountId,
      transactionId: TransactionId,
      amount: Amount): AccountAggregateOpF[Rejection Either Done] =
    Free.liftF(CreditAccount(accountId, transactionId, amount))

  case class OpenAccount(accountId: AccountId)
      extends AccountAggregateOp[Either[Rejection, Done]]

  case class AuthorizeTransaction(accountId: AccountId,
                                  transactionId: TransactionId,
                                  amount: Amount)
      extends AccountAggregateOp[Either[AuthorizeTransactionRejection, Done]]

  case class VoidTransaction(accountId: AccountId,
                             transactionId: TransactionId)
      extends AccountAggregateOp[Rejection Either Done]

  case class CaptureTransaction(accountId: AccountId,
                                transactionId: TransactionId,
                                amount: Amount)
      extends AccountAggregateOp[Rejection Either Done]

  case class CreditAccount(accountId: AccountId,
                           transactionId: TransactionId,
                           amount: Amount)
      extends AccountAggregateOp[Rejection Either Done]

  sealed trait Rejection

  sealed trait AuthorizeTransactionRejection

  case object DuplicateTransaction extends AuthorizeTransactionRejection

  case object AccountDoesNotExist
      extends Rejection
      with AuthorizeTransactionRejection

  case object InsufficientFunds
      extends Rejection
      with AuthorizeTransactionRejection

  case object AccountExists extends Rejection

  case object HoldNotFound extends Rejection
}

sealed trait AccountAggregateOp[+R] {
  def accountId: AccountId
}
