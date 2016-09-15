package aecor.example.domain
import java.util.UUID

import aecor.core.aggregate.AggregateBehavior.syntax._
import aecor.core.aggregate._
import aecor.core.message.Correlation
import aecor.example.domain.CardAuthorization.{Event, State}
import cats.free.Free
import io.circe.{Decoder, Encoder}
import io.circe.generic.auto._
import aecor.util.function._
import akka.Done
import cats.data.Xor

import scala.collection.immutable.Seq

case class TransactionId(value: String) extends AnyVal
object CardAuthorization {

  sealed trait DeclineReason
  case object InsufficientFunds extends DeclineReason
  case object AccountDoesNotExist extends DeclineReason
  case object Unknown extends DeclineReason

  type DSL[Response] = Free[Command, Response]

  sealed trait Command[Response] {
    def cardAuthorizationId: CardAuthorizationId
    def lift: DSL[Response] = Free.liftF(this)
  }

  type CommandResult[+Rejection] = Xor[Rejection, Done]

  object Command {
    def createCardAuthorization(cardAuthorizationId: CardAuthorizationId, accountId: AccountId, amount: Amount, acquireId: AcquireId, terminalId: TerminalId): DSL[CommandResult[CreateCardAuthorizationRejection]] =
      CreateCardAuthorization(cardAuthorizationId, accountId, amount, acquireId, terminalId).lift
  }
  case class CreateCardAuthorization(cardAuthorizationId: CardAuthorizationId, accountId: AccountId, amount: Amount, acquireId: AcquireId, terminalId: TerminalId) extends Command[CommandResult[CreateCardAuthorizationRejection]]
  case class DeclineCardAuthorization(cardAuthorizationId: CardAuthorizationId, reason: DeclineReason) extends Command[CommandResult[DeclineCardAuthorizationRejection]]
  case class AcceptCardAuthorization(cardAuthorizationId: CardAuthorizationId) extends Command[CommandResult[AcceptCardAuthorizationRejection]]


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

  sealed trait State {
    def applyEvent(event: Event): State =
      handle(this, event) {
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
  case object Initial extends State
  case class Created(id: CardAuthorizationId) extends State
  case class Accepted(id: CardAuthorizationId) extends State
  case class Declined(id: CardAuthorizationId) extends State

  implicit def correlation: Correlation[Command[_]] = Correlation.instance(_.cardAuthorizationId.value)

  implicit val name: AggregateName[CardAuthorization] = AggregateName.instance("CardAuthorization")

  implicit object behavior extends AggregateBehavior[CardAuthorization] {
    type Command[X] = CardAuthorization.Command[X]
    type Event = CardAuthorization.Event

    override type State = CardAuthorization.State

    override def handleCommand[Response](a: CardAuthorization)(state: State, command: Command[Response]) =
      a.handleCommand(state, command)


    override def init: State = Initial

    override def applyEvent(state: State, event: Event): State =
      state.applyEvent(event)
  }

  def apply(): CardAuthorization = new CardAuthorization()
}
case class CardAuthorizationId(value: String) extends AnyVal
case class CardNumber(value: String) extends AnyVal
case class AcquireId(value: Long) extends AnyVal
case class TerminalId(value: Long) extends AnyVal


import aecor.example.domain.CardAuthorization._

class CardAuthorization {
  def handleCommand[Response](state: State, command: Command[Response]): (Response, Seq[Event]) =
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
}