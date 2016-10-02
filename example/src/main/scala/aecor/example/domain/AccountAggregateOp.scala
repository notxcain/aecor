package aecor.example.domain

import aecor.core.message.Correlation
import akka.Done
import cats.data.Xor
import cats.free.Free

object AccountAggregateOp {
  implicit def correlation: Correlation[AccountAggregateOp[_]] =
    Correlation.instance(_.accountId.value)

  type AccountAggregateOpF[A] = Free[AccountAggregateOp, A]

  def openAccount(accountId: AccountId): AccountAggregateOpF[Rejection Xor Done] =
    Free.liftF(OpenAccount(accountId))

  def authorizeTransaction(accountId: AccountId, transactionId: TransactionId, amount: Amount): AccountAggregateOpF[AuthorizeTransactionRejection Xor Done] =
    Free.liftF(AuthorizeTransaction(accountId, transactionId, amount))

  def voidTransaction(accountId: AccountId, transactionId: TransactionId): AccountAggregateOpF[Rejection Xor Done] =
    Free.liftF(VoidTransaction(accountId, transactionId))

  def captureTransaction(accountId: AccountId, transactionId: TransactionId, amount: Amount): AccountAggregateOpF[Rejection Xor Done] =
    Free.liftF(CaptureTransaction(accountId, transactionId, amount))

  def creditAccount(accountId: AccountId, transactionId: TransactionId, amount: Amount): AccountAggregateOpF[Rejection Xor Done] =
    Free.liftF(CreditAccount(accountId, transactionId, amount))

  case class OpenAccount(accountId: AccountId) extends AccountAggregateOp[Rejection Xor Done]

  case class AuthorizeTransaction(accountId: AccountId, transactionId: TransactionId, amount: Amount) extends AccountAggregateOp[AuthorizeTransactionRejection Xor Done]

  case class VoidTransaction(accountId: AccountId, transactionId: TransactionId) extends AccountAggregateOp[Rejection Xor Done]

  case class CaptureTransaction(accountId: AccountId, transactionId: TransactionId, amount: Amount) extends AccountAggregateOp[Rejection Xor Done]

  case class CreditAccount(accountId: AccountId, transactionId: TransactionId, amount: Amount) extends AccountAggregateOp[Rejection Xor Done]

  sealed trait Rejection

  sealed trait AuthorizeTransactionRejection

  case object DuplicateTransaction extends AuthorizeTransactionRejection

  case object AccountDoesNotExist extends Rejection with AuthorizeTransactionRejection

  case object InsufficientFunds extends Rejection with AuthorizeTransactionRejection

  case object AccountExists extends Rejection

  case object HoldNotFound extends Rejection
}

sealed trait AccountAggregateOp[R] {
  def accountId: AccountId
}