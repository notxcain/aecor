package aecor.example.domain.transaction

import java.time.Instant

import aecor.example.domain.Amount
import aecor.example.domain.account.AccountId
import aecor.example.persistentEncoderUtil
import aecor.runtime.akkapersistence.serialization.{ PersistentDecoder, PersistentEncoder }
import io.circe.java8.time._
import io.circe.generic.auto._

sealed abstract class TransactionEvent extends Product with Serializable {
  def transactionId: TransactionId
}

object TransactionEvent {
  final case class TransactionCreated(transactionId: TransactionId,
                                      fromAccount: From[AccountId],
                                      toAccount: To[AccountId],
                                      amount: Amount,
                                      timestamp: Instant)
      extends TransactionEvent

  final case class TransactionAuthorized(transactionId: TransactionId, timestamp: Instant)
      extends TransactionEvent

  case class TransactionFailed(transactionId: TransactionId, reason: String, timestamp: Instant)
      extends TransactionEvent
  case class TransactionSucceeded(transactionId: TransactionId, timestamp: Instant)
      extends TransactionEvent

  implicit val encoder: PersistentEncoder[TransactionEvent] =
    persistentEncoderUtil.circePersistentEncoder[TransactionEvent]
  implicit val decoder: PersistentDecoder[TransactionEvent] =
    persistentEncoderUtil.circePersistentDecoder[TransactionEvent]
}
