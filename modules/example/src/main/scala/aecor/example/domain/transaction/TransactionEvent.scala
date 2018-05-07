package aecor.example.domain.transaction

import aecor.data.Enriched
import aecor.example.domain.{ Amount, Timestamp }
import aecor.example.domain.account.AccountId
import aecor.example.persistentEncoderUtil
import aecor.runtime.akkapersistence.serialization.{ PersistentDecoder, PersistentEncoder }
import io.circe.java8.time._
import io.circe.generic.auto._

sealed abstract class TransactionEvent extends Product with Serializable

object TransactionEvent {
  final case class TransactionCreated(fromAccount: From[AccountId],
                                      toAccount: To[AccountId],
                                      amount: Amount)
      extends TransactionEvent

  final case object TransactionAuthorized extends TransactionEvent

  case class TransactionFailed(reason: String) extends TransactionEvent
  case object TransactionSucceeded extends TransactionEvent

  implicit val encoder: PersistentEncoder[Enriched[Timestamp, TransactionEvent]] =
    persistentEncoderUtil.circePersistentEncoder[Enriched[Timestamp, TransactionEvent]]
  implicit val decoder: PersistentDecoder[Enriched[Timestamp, TransactionEvent]] =
    persistentEncoderUtil.circePersistentDecoder[Enriched[Timestamp, TransactionEvent]]
}
