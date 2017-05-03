package aecor.example.domain.transaction

import aecor.example.domain.Amount
import aecor.example.domain.account.AccountId
import aecor.example.domain.transaction.TransactionAggregate.TransactionInfo
import io.aecor.liberator.macros.{ algebra, term }

final case class TransactionId(value: String) extends AnyVal
final case class From[A](value: A) extends AnyVal
final case class To[A](value: A) extends AnyVal

@algebra('transactionId)
trait TransactionAggregate[F[_]] {
  def createTransaction(transactionId: TransactionId,
                        fromAccountId: From[AccountId],
                        toAccountId: To[AccountId],
                        amount: Amount): F[Unit]
  def authorizeTransaction(transactionId: TransactionId): F[Either[String, Unit]]
  def failTransaction(transactionId: TransactionId, reason: String): F[Either[String, Unit]]
  def succeedTransaction(transactionId: TransactionId): F[Either[String, Unit]]
  def getTransactionInfo(transactionId: TransactionId): F[Option[TransactionInfo]]
}

object TransactionAggregate {
  final case class TransactionInfo(transactionId: TransactionId,
                                   fromAccountId: From[AccountId],
                                   toAccountId: To[AccountId],
                                   amount: Amount,
                                   succeeded: Option[Boolean])
}
