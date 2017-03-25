package aecor.example.domain

import aecor.example.persistentEncoderUtil
import aecor.aggregate.serialization.{ PersistentDecoder, PersistentEncoder }
import io.circe.generic.auto._
sealed trait CardAuthorizationAggregateEvent extends Product with Serializable {
  def cardAuthorizationId: CardAuthorizationId
}

object CardAuthorizationAggregateEvent {
  case class CardAuthorizationCreated(cardAuthorizationId: CardAuthorizationId,
                                      accountId: AccountId,
                                      amount: Amount,
                                      acquireId: AcquireId,
                                      terminalId: TerminalId,
                                      transactionId: TransactionId)
      extends CardAuthorizationAggregateEvent
  case class CardAuthorizationDeclined(cardAuthorizationId: CardAuthorizationId, reason: String)
      extends CardAuthorizationAggregateEvent
  case class CardAuthorizationAccepted(cardAuthorizationId: CardAuthorizationId)
      extends CardAuthorizationAggregateEvent

  implicit val encoder: PersistentEncoder[CardAuthorizationAggregateEvent] =
    persistentEncoderUtil.circePersistentEncoder
  implicit val decoder: PersistentDecoder[CardAuthorizationAggregateEvent] =
    persistentEncoderUtil.circePersistentDecoder
}
