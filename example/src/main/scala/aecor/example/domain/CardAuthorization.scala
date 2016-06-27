package aecor.example.domain
import java.util.UUID

import aecor.core.entity.CommandHandlerResult._
import aecor.core.entity._
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

  def initialState: State = Initial

  implicit def commandContract[Rejection]: CommandContract.Aux[CardAuthorization, Command[Rejection], Rejection] = CommandContract.instance
  implicit def correlation[Rejection]: Correlation[Command[Rejection]] = Correlation.instance(_.cardAuthorizationId.value)
  implicit val name: EntityName[CardAuthorization] = EntityName.instance("CardAuthorization")

  implicit def behavior[Rejection]: EntityBehavior[CardAuthorization, State, Command[Rejection], Event, Rejection] = new EntityBehavior[CardAuthorization, State, Command[Rejection], Event, Rejection] {
    override def initialState(entity: CardAuthorization): State = Initial

    override def commandHandler(entity: CardAuthorization): CommandHandler[State, Command[Rejection], Event, Rejection] =
      CommandHandler.instance {
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

    override def eventProjector(entity: CardAuthorization): EventProjector[State, Event] =
      EventProjector.instance {
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
  }
  def apply(): CardAuthorization = new CardAuthorization {}
}
case class CardAuthorizationId(value: String) extends AnyVal
case class CardNumber(value: String) extends AnyVal
case class AcquireId(value: Long) extends AnyVal
case class TerminalId(value: Long) extends AnyVal


sealed trait CardAuthorization