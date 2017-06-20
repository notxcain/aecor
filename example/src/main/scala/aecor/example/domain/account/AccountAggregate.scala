package aecor.example.domain.account

import aecor.example.AnyValCirceEncoding
import aecor.example.domain.Amount
import aecor.example.domain.account.AccountAggregate.Rejection
import aecor.example.domain.transaction.TransactionId
import io.aecor.liberator.macros.algebra
import io.circe.{ Decoder, Encoder }

@algebra('accountId)
trait AccountAggregate[F[_]] {
  def openAccount(accountId: AccountId): F[Either[Rejection, Unit]]
  def creditAccount(accountId: AccountId,
                    transactionId: AccountTransactionId,
                    amount: Amount): F[Either[Rejection, Unit]]
  def debitAccount(accountId: AccountId,
                   transactionId: AccountTransactionId,
                   amount: Amount): F[Either[Rejection, Unit]]
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
object AccountAggregate {
  sealed trait Rejection extends Product with Serializable

  sealed trait AuthorizeTransactionRejection

  case object DuplicateTransaction extends AuthorizeTransactionRejection

  case object AccountDoesNotExist extends Rejection with AuthorizeTransactionRejection

  case object InsufficientFunds extends Rejection with AuthorizeTransactionRejection

  case object AccountExists extends Rejection

  case object HoldNotFound extends Rejection
}
