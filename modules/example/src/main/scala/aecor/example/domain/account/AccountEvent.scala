package aecor.example.domain.account

import aecor.data.Enriched
import aecor.example.domain.{ Amount, Timestamp }
import aecor.example.persistentEncoderUtil
import aecor.runtime.akkapersistence.serialization.{ PersistentDecoder, PersistentEncoder }
import io.circe.java8.time._
import io.circe.generic.auto._

sealed abstract class AccountEvent extends Product with Serializable

object AccountEvent {
  case class AccountOpened(checkBalance: Boolean) extends AccountEvent

  case class AccountDebited(transactionId: AccountTransactionId, amount: Amount)
      extends AccountEvent

  case class AccountCredited(transactionId: AccountTransactionId, amount: Amount)
      extends AccountEvent

  implicit val encoder: PersistentEncoder[Enriched[Timestamp, AccountEvent]] =
    persistentEncoderUtil.circePersistentEncoder(io.circe.generic.semiauto.deriveEncoder)
  implicit val decoder: PersistentDecoder[Enriched[Timestamp, AccountEvent]] =
    persistentEncoderUtil.circePersistentDecoder(io.circe.generic.semiauto.deriveDecoder)
}
