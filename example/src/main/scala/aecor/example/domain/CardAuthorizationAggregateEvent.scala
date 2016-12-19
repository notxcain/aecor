package aecor.example.domain

import aecor.example.domain.CardAuthorizationAggregateOp.DeclineReason

sealed trait CardAuthorizationAggregateEvent {
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
  case class CardAuthorizationDeclined(
      cardAuthorizationId: CardAuthorizationId,
      reason: DeclineReason)
      extends CardAuthorizationAggregateEvent
  case class CardAuthorizationAccepted(
      cardAuthorizationId: CardAuthorizationId)
      extends CardAuthorizationAggregateEvent
}
