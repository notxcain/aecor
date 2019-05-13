package aecor.example.account

import aecor.encoding.WireProtocol
import aecor.example.AnyValCirceEncoding
import aecor.example.common.Amount
import aecor.example.transaction.TransactionId
import aecor.macros.boopickle.BoopickleWireProtocol
import cats.tagless.{ Derive, FunctorK }
import io.circe.{ Decoder, Encoder }

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

trait Algebra[F[_]] {
  def open(checkBalance: Boolean): F[Unit]
  def credit(transactionId: AccountTransactionId, amount: Amount): F[Unit]
  def debit(transactionId: AccountTransactionId, amount: Amount): F[Unit]
}

object Algebra {
  import boopickle.Default._
  implicit def functorK: FunctorK[Algebra] = Derive.functorK
  implicit def wireProtocol: WireProtocol[Algebra] = BoopickleWireProtocol.derive
}
