package aecor.example.domain.transaction

import aecor.encoding.{ KeyDecoder, KeyEncoder }
import aecor.example.domain.Amount
import aecor.example.domain.account.AccountId
import aecor.example.domain.transaction.TransactionAggregate.TransactionInfo
import aecor.macros.boopickleWireProtocol
import boopickle.Default._

final case class TransactionId(value: String) extends AnyVal
object TransactionId {
  implicit val keyEncoder: KeyEncoder[TransactionId] = KeyEncoder.anyVal[TransactionId]
  implicit val keyDecoder: KeyDecoder[TransactionId] = KeyDecoder.anyVal[TransactionId]
}

final case class From[A](value: A) extends AnyVal
final case class To[A](value: A) extends AnyVal

@boopickleWireProtocol
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
