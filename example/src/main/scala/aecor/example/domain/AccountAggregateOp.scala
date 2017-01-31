package aecor.example.domain

import akka.Done

object AccountAggregateOp {
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

sealed trait AccountAggregateOp[R] {
  def accountId: AccountId
}
