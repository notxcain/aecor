package aecor.example.domain.account

import aecor.example.AnyValCirceEncoding
import aecor.example.domain.Amount
import aecor.example.domain.account.Account.Rejection
import aecor.example.domain.transaction.TransactionId
import aecor.macros.boopickleWireProtocol
import io.circe.{ Decoder, Encoder }

@boopickleWireProtocol
trait Account[F[_]] {
  def open(checkBalance: Boolean): F[Either[Rejection, Unit]]
  def credit(transactionId: AccountTransactionId, amount: Amount): F[Either[Rejection, Unit]]
  def debit(transactionId: AccountTransactionId, amount: Amount): F[Either[Rejection, Unit]]
}

object Account {
  import boopickle.Default._

  implicit val rejectionPickler =
    compositePickler[Rejection]
      .addConcreteType[AccountDoesNotExist.type]
      .addConcreteType[InsufficientFunds.type]
      .addConcreteType[AccountExists.type]
      .addConcreteType[HoldNotFound.type]

  sealed trait Rejection extends Product with Serializable
  case object AccountDoesNotExist extends Rejection
  case object InsufficientFunds extends Rejection
  case object AccountExists extends Rejection
  case object HoldNotFound extends Rejection
}

final case class AccountTransactionId(baseTransactionId: TransactionId,
                                      kind: AccountTransactionKind)

object AccountTransactionId extends AnyValCirceEncoding {
  implicit def decoder: Decoder[AccountTransactionId] = io.circe.generic.semiauto.deriveDecoder
  implicit def encoder: Encoder[AccountTransactionId] = io.circe.generic.semiauto.deriveEncoder
}

sealed abstract class AccountTransactionKind
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
