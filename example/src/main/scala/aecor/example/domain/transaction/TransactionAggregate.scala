package aecor.example.domain.transaction

import aecor.encoding.{ KeyDecoder, KeyEncoder }
import aecor.example.domain.Amount
import aecor.example.domain.account.AccountId
import aecor.example.domain.transaction.TransactionAggregate.TransactionInfo
import io.aecor.liberator.macros.algebra

final case class TransactionId(value: String) extends AnyVal

object TransactionId {
  implicit val keyEncoder: KeyEncoder[TransactionId] =
    KeyEncoder[String].contramap(_.value)
  implicit val keyDecoder: KeyDecoder[TransactionId] = KeyDecoder[String].map(TransactionId(_))

}

final case class From[A](value: A) extends AnyVal
final case class To[A](value: A) extends AnyVal

@algebra
trait TransactionAggregate[F[_]] {
  def createTransaction(fromAccountId: From[AccountId],
                        toAccountId: To[AccountId],
                        amount: Amount): F[Unit]
  def authorizeTransaction: F[Either[String, Unit]]
  def failTransaction(reason: String): F[Either[String, Unit]]
  def succeedTransaction: F[Either[String, Unit]]
  def getTransactionInfo: F[Option[TransactionInfo]]
}

object TransactionAggregate {
  final case class TransactionInfo(fromAccountId: From[AccountId],
                                   toAccountId: To[AccountId],
                                   amount: Amount,
                                   succeeded: Option[Boolean])
}
