package aecor.example.domain

import aecor.example.persistentEncoderUtil
import aecor.runtime.akkapersistence.serialization.{ PersistentDecoder, PersistentEncoder }
import io.circe.generic.auto._

sealed abstract class AccountAggregateEvent extends Product with Serializable {
  def accountId: AccountId
}

object AccountAggregateEvent {
  case class AccountOpened(accountId: AccountId) extends AccountAggregateEvent

  case class TransactionAuthorized(accountId: AccountId,
                                   transactionId: TransactionId,
                                   amount: Amount)
      extends AccountAggregateEvent

  case class TransactionVoided(accountId: AccountId, transactionId: TransactionId)
      extends AccountAggregateEvent

  case class TransactionCaptured(accountId: AccountId,
                                 transactionId: TransactionId,
                                 amount: Amount)
      extends AccountAggregateEvent

  case class AccountCredited(accountId: AccountId, transactionId: TransactionId, amount: Amount)
      extends AccountAggregateEvent

  implicit val encoder: PersistentEncoder[AccountAggregateEvent] =
    persistentEncoderUtil.circePersistentEncoder
  implicit val decoder: PersistentDecoder[AccountAggregateEvent] =
    persistentEncoderUtil.circePersistentDecoder
}
