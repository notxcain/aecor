package aecor.example.domain.account

import aecor.example.domain.Amount
import aecor.example.persistentEncoderUtil
import aecor.runtime.akkapersistence.serialization.{ PersistentDecoder, PersistentEncoder }
import io.circe.generic.auto._

sealed abstract class AccountEvent extends Product with Serializable {
  def accountId: AccountId
}

object AccountEvent {
  case class AccountOpened(accountId: AccountId, ts: Long) extends AccountEvent

  case class AccountDebited(accountId: AccountId,
                            transactionId: AccountTransactionId,
                            amount: Amount)
      extends AccountEvent

  case class AccountCredited(accountId: AccountId,
                             transactionId: AccountTransactionId,
                             amount: Amount)
      extends AccountEvent

  implicit val encoder: PersistentEncoder[AccountEvent] =
    persistentEncoderUtil.circePersistentEncoder(io.circe.generic.semiauto.deriveEncoder)
  implicit val decoder: PersistentDecoder[AccountEvent] =
    persistentEncoderUtil.circePersistentDecoder(io.circe.generic.semiauto.deriveDecoder)
}
