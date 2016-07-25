package aecor.example.domain
import java.util.UUID

import aecor.core.aggregate.AggregateBehavior.syntax._
import aecor.core.aggregate._
import aecor.core.message.Correlation
import io.circe.{Decoder, Encoder}
import io.circe.generic.auto._

case class TransactionId(value: String) extends AnyVal
object CardAuthorization {

  sealed trait DeclineReason
  case object InsufficientFunds extends DeclineReason
  case object AccountDoesNotExist extends DeclineReason

  sealed trait Command[Rejection] {
    def cardAuthorizationId: CardAuthorizationId
  }
  case class CreateCardAuthorization(cardAuthorizationId: CardAuthorizationId, accountId: AccountId, amount: Amount, acquireId: AcquireId, terminalId: TerminalId) extends Command[CreateCardAuthorizationRejection]
  case class DeclineCardAuthorization(cardAuthorizationId: CardAuthorizationId, reason: DeclineReason) extends Command[DeclineCardAuthorizationRejection]
  case class AcceptCardAuthorization(cardAuthorizationId: CardAuthorizationId) extends Command[AcceptCardAuthorizationRejection]

  sealed trait Event {
    def cardAuthorizationId: CardAuthorizationId
  }
  case class CardAuthorizationCreated(cardAuthorizationId: CardAuthorizationId, accountId: AccountId, amount: Amount, acquireId: AcquireId, terminalId: TerminalId, transactionId: TransactionId) extends Event
  case class CardAuthorizationDeclined(cardAuthorizationId: CardAuthorizationId, reason: DeclineReason) extends Event
  case class CardAuthorizationAccepted(cardAuthorizationId: CardAuthorizationId) extends Event
  implicit val encoder: Encoder[Event] = shapeless.cachedImplicit
  implicit val decoder: Decoder[Event] = shapeless.cachedImplicit

  sealed trait CreateCardAuthorizationRejection
  sealed trait DeclineCardAuthorizationRejection
  sealed trait AcceptCardAuthorizationRejection
  case object DoesNotExists extends DeclineCardAuthorizationRejection with AcceptCardAuthorizationRejection
  case object AlreadyExists extends CreateCardAuthorizationRejection
  case object AlreadyDeclined extends DeclineCardAuthorizationRejection with AcceptCardAuthorizationRejection
  case object AlreadyAccepted extends DeclineCardAuthorizationRejection with AcceptCardAuthorizationRejection

  sealed trait State
  case object Initial extends State
  case class Created(id: CardAuthorizationId) extends State
  case class Accepted(id: CardAuthorizationId) extends State
  case class Declined(id: CardAuthorizationId) extends State

  implicit def commandContract[Rejection]: CommandContract.Aux[CardAuthorization, Command[Rejection], Rejection] = CommandContract.instance

  implicit def correlation[Rejection]: Correlation[Command[Rejection]] = Correlation.instance(_.cardAuthorizationId.value)

  implicit val name: AggregateName[CardAuthorization] = AggregateName.instance("CardAuthorization")

  implicit def behavior[R0] =
    new AggregateBehavior[CardAuthorization] {
      type Cmd = Command[R0]
      type Evt = Event
      type Rjn = R0
      override def handleCommand(a: CardAuthorization)(command: Command[R0]): NowOrLater[AggregateDecision[R0, Event]] =
        a.handleCommand(command)
      override def applyEvent(a: CardAuthorization)(event: Event): CardAuthorization =
        a.applyEvent(event)
    }

  def apply(): CardAuthorization = CardAuthorization(Initial)
}
case class CardAuthorizationId(value: String) extends AnyVal
case class CardNumber(value: String) extends AnyVal
case class AcquireId(value: Long) extends AnyVal
case class TerminalId(value: Long) extends AnyVal


import CardAuthorization._

case class CardAuthorization(state: State) {
  def handleCommand[R](command: Command[R]): NowOrLater[AggregateDecision[R, Event]] =
    handle(state, command) {
      case Initial => {
        case CreateCardAuthorization(cardAuthorizationId, accountId, amount, acquireId, terminalId) =>
          accept(CardAuthorizationCreated(cardAuthorizationId, accountId, amount, acquireId, terminalId, TransactionId(UUID.randomUUID().toString)))
        case c: AcceptCardAuthorization =>
          reject(DoesNotExists)
        case c: DeclineCardAuthorization =>
          reject(DoesNotExists)
      }
      case Created(id) => {
        case e: AcceptCardAuthorization =>
          accept(CardAuthorizationAccepted(id))
        case e: DeclineCardAuthorization =>
          accept(CardAuthorizationDeclined(id, e.reason))
        case e: CreateCardAuthorization =>
          reject(AlreadyExists)
      }
      case Accepted(id) => {
        case e: AcceptCardAuthorization => reject(AlreadyAccepted)
        case e: DeclineCardAuthorization => reject(AlreadyAccepted)
        case e: CreateCardAuthorization => reject(AlreadyExists)
      }
      case Declined(id) => {
        case e: AcceptCardAuthorization => reject(AlreadyDeclined)
        case e: DeclineCardAuthorization => reject(AlreadyDeclined)
        case e: CreateCardAuthorization => reject(AlreadyExists)
      }
    }

  def applyEvent(event: Event): CardAuthorization = copy(
    state = handle(state, event) {
      case Initial => {
        case CardAuthorizationCreated(cardAuthorizationId, accountId, amount, acquireId, terminalId, transactionId) =>
          Created(cardAuthorizationId)
        case other =>
          throw new IllegalArgumentException(s"Unexpected event $other")
      }
      case self: Created => {
        case e: CardAuthorizationCreated => self
        case e: CardAuthorizationAccepted => Accepted(self.id)
        case e: CardAuthorizationDeclined => Declined(self.id)
      }
      case self: Accepted => {
        case e: CardAuthorizationCreated => self
        case e: CardAuthorizationAccepted => Accepted(self.id)
        case e: CardAuthorizationDeclined => Declined(self.id)
      }
      case self: Declined => {
        case e: CardAuthorizationCreated => self
        case e: CardAuthorizationAccepted => Accepted(self.id)
        case e: CardAuthorizationDeclined => Declined(self.id)
      }
    }
  )
}