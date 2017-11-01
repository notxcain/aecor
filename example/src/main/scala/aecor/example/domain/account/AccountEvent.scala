package aecor.example.domain.account

import java.time.Instant

import aecor.example.domain.Amount
import aecor.example.persistentEncoderUtil
import aecor.runtime.akkapersistence.serialization.{ PersistentDecoder, PersistentEncoder }
import io.circe.java8.time._
import io.circe.generic.auto._

final case class EventEnvelope[M, E](meta: M, event: E)

final case class AccountEventMeta(timestamp: Instant)

sealed abstract class AccountEvent extends Product with Serializable

object AccountEvent {
  case class AccountOpened(checkBalance: Boolean) extends AccountEvent

  case class AccountDebited(transactionId: AccountTransactionId, amount: Amount)
      extends AccountEvent

  case class AccountCredited(transactionId: AccountTransactionId, amount: Amount)
      extends AccountEvent

  implicit val encoder: PersistentEncoder[EventEnvelope[AccountEventMeta, AccountEvent]] =
    persistentEncoderUtil.circePersistentEncoder(io.circe.generic.semiauto.deriveEncoder)
  implicit val decoder: PersistentDecoder[EventEnvelope[AccountEventMeta, AccountEvent]] =
    persistentEncoderUtil.circePersistentDecoder(io.circe.generic.semiauto.deriveDecoder)
}
