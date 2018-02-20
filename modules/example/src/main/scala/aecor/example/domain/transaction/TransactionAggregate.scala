package aecor.example.domain.transaction

import aecor.example.domain.Amount
import aecor.example.domain.account.AccountId
import aecor.example.domain.transaction.TransactionAggregate.TransactionInfo
import aecor.macros.wireProtocol

final case class TransactionId(value: String) extends AnyVal

final case class From[A](value: A) extends AnyVal
final case class To[A](value: A) extends AnyVal
@wireProtocol
trait TransactionAggregate[F[_]] {
  def create(fromAccountId: From[AccountId], toAccountId: To[AccountId], amount: Amount): F[Unit]
  def authorize: F[Either[String, Unit]]
  def fail(reason: String): F[Either[String, Unit]]
  def succeed: F[Either[String, Unit]]
  def getInfo: F[Option[TransactionInfo]]
}

object TransactionAggregate {
  final case class TransactionInfo(fromAccountId: From[AccountId],
                                   toAccountId: To[AccountId],
                                   amount: Amount,
                                   succeeded: Option[Boolean])
}
