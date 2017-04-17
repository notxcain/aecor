package aecor.example.domain.transaction

import aecor.example.domain.Amount
import aecor.example.domain.account.AccountId
import aecor.example.persistentEncoderUtil
import aecor.runtime.akkapersistence.serialization.{ PersistentDecoder, PersistentEncoder }
import io.circe.generic.auto._

sealed abstract class TransactionEvent extends Product with Serializable {
  def transactionId: TransactionId
}

object TransactionEvent {
  final case class TransactionCreated(transactionId: TransactionId,
                                      fromAccount: From[AccountId],
                                      toAccount: To[AccountId],
                                      amount: Amount)
      extends TransactionEvent

  final case class TransactionAuthorized(transactionId: TransactionId) extends TransactionEvent

  case class TransactionFailed(transactionId: TransactionId, reason: String)
      extends TransactionEvent
  case class TransactionSucceeded(transactionId: TransactionId) extends TransactionEvent

  implicit val encoder: PersistentEncoder[TransactionEvent] =
    persistentEncoderUtil.circePersistentEncoder
  implicit val decoder: PersistentDecoder[TransactionEvent] =
    persistentEncoderUtil.circePersistentDecoder
}
