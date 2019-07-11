package aecor.example.transaction

import aecor.encoding.{ KeyDecoder, KeyEncoder, WireProtocol }
import aecor.example.account.AccountId
import aecor.example.common.Amount
import aecor.example.transaction.Algebra.TransactionInfo
import aecor.macros.boopickle.BoopickleWireProtocol
import cats.tagless.{ Derive, FunctorK }

final case class TransactionId(value: String) extends AnyVal
object TransactionId {
  implicit val keyEncoder: KeyEncoder[TransactionId] = KeyEncoder.anyVal[TransactionId]
  implicit val keyDecoder: KeyDecoder[TransactionId] = KeyDecoder.anyVal[TransactionId]
}

final case class From[A](value: A) extends AnyVal
final case class To[A](value: A) extends AnyVal

trait Algebra[F[_]] {
  def create(fromAccountId: From[AccountId], toAccountId: To[AccountId], amount: Amount): F[Unit]
  def authorize: F[Unit]
  def fail(reason: String): F[Unit]
  def succeed: F[Unit]
  def getInfo: F[TransactionInfo]
}

object Algebra {
  import boopickle.Default._
  final case class TransactionInfo(fromAccountId: From[AccountId],
                                   toAccountId: To[AccountId],
                                   amount: Amount,
                                   succeeded: Option[Boolean])

  implicit def functorK: FunctorK[Algebra] = Derive.functorK
  implicit def wireProtocol: WireProtocol[Algebra] = BoopickleWireProtocol.derive
}
