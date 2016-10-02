package aecor.example.domain

sealed trait AccountAggregateEvent {
  def accountId: AccountId
}

object AccountAggregateEvent {
  case class AccountOpened(accountId: AccountId) extends AccountAggregateEvent

  case class TransactionAuthorized(accountId: AccountId, transactionId: TransactionId, amount: Amount) extends AccountAggregateEvent

  case class TransactionVoided(accountId: AccountId, transactionId: TransactionId) extends AccountAggregateEvent

  case class TransactionCaptured(accountId: AccountId, transactionId: TransactionId, amount: Amount) extends AccountAggregateEvent

  case class AccountCredited(accountId: AccountId, transactionId: TransactionId, amount: Amount) extends AccountAggregateEvent
}