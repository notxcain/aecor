package aecor.example.domain

import akka.Done

sealed trait TransactionOp[Response] {
  def cardAuthorizationId: CardAuthorizationId
}

object TransactionOp {
  type CommandResult[+Rejection] = Either[Rejection, Done]

  case class CreateTransaction(cardAuthorizationId: CardAuthorizationId,
                               accountId: AccountId,
                               amount: Amount,
                               acquireId: AcquireId,
                               terminalId: TerminalId)
      extends TransactionOp[CommandResult[CreateCardAuthorizationRejection]]
  case class DeclineCardAuthorization(cardAuthorizationId: CardAuthorizationId, reason: String)
      extends TransactionOp[CommandResult[DeclineCardAuthorizationRejection]]
  case class AcceptCardAuthorization(cardAuthorizationId: CardAuthorizationId)
      extends TransactionOp[CommandResult[AcceptCardAuthorizationRejection]]

  sealed trait CreateCardAuthorizationRejection
  sealed trait DeclineCardAuthorizationRejection
  sealed trait AcceptCardAuthorizationRejection
  case object DoesNotExists
      extends DeclineCardAuthorizationRejection
      with AcceptCardAuthorizationRejection
  case object AlreadyExists extends CreateCardAuthorizationRejection
  case object AlreadyDeclined
      extends DeclineCardAuthorizationRejection
      with AcceptCardAuthorizationRejection
  case object AlreadyAccepted
      extends DeclineCardAuthorizationRejection
      with AcceptCardAuthorizationRejection
}
