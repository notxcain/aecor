package aecor.example.account

import aecor.example.AnyValCirceEncoding
import aecor.example.common.Amount
import aecor.example.transaction.TransactionId
import aecor.macros.boopickleWireProtocol
import io.circe.{ Decoder, Encoder }
import boopickle.Default._
import cats.tagless.autoFunctorK

final case class AccountTransactionId(baseTransactionId: TransactionId,
                                      kind: AccountTransactionKind)

object AccountTransactionId extends AnyValCirceEncoding {
  implicit def decoder: Decoder[AccountTransactionId] = io.circe.generic.semiauto.deriveDecoder
  implicit def encoder: Encoder[AccountTransactionId] = io.circe.generic.semiauto.deriveEncoder
}

sealed abstract class AccountTransactionKind extends Product with Serializable
object AccountTransactionKind {
  case object Normal extends AccountTransactionKind
  case object Revert extends AccountTransactionKind
  implicit val decoder: Decoder[AccountTransactionKind] = Decoder[String].emap {
    case "Normal" => Right(Normal)
    case "Revert" => Right(Revert)
    case _        => Left("Unknown AccountTransactionKind")
  }
  implicit val encoder: Encoder[AccountTransactionKind] = Encoder[String].contramap(_.toString)
}
@boopickleWireProtocol
@autoFunctorK(false)
trait Algebra[F[_]] {
  def open(checkBalance: Boolean): F[Unit]
  def credit(transactionId: AccountTransactionId, amount: Amount): F[Unit]
  def debit(transactionId: AccountTransactionId, amount: Amount): F[Unit]
}

object Algebra
