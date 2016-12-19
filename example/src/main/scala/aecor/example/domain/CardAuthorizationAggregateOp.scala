package aecor.example.domain

import akka.Done

sealed trait CardAuthorizationAggregateOp[Response] {
  def cardAuthorizationId: CardAuthorizationId
}

object CardAuthorizationAggregateOp {
  type CommandResult[+Rejection] = Either[Rejection, Done]

  sealed trait DeclineReason
  case object InsufficientFunds extends DeclineReason
  case object AccountDoesNotExist extends DeclineReason
  case object Unknown extends DeclineReason

  case class CreateCardAuthorization(cardAuthorizationId: CardAuthorizationId,
                                     accountId: AccountId,
                                     amount: Amount,
                                     acquireId: AcquireId,
                                     terminalId: TerminalId)
      extends CardAuthorizationAggregateOp[
        CommandResult[CreateCardAuthorizationRejection]]
  case class DeclineCardAuthorization(cardAuthorizationId: CardAuthorizationId,
                                      reason: DeclineReason)
      extends CardAuthorizationAggregateOp[
        CommandResult[DeclineCardAuthorizationRejection]]
  case class AcceptCardAuthorization(cardAuthorizationId: CardAuthorizationId)
      extends CardAuthorizationAggregateOp[
        CommandResult[AcceptCardAuthorizationRejection]]

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
