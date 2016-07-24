package aecor.example.domain

import aecor.core.aggregate.AggregateDecision._
import aecor.core.aggregate._
import aecor.core.message.Correlation
import aecor.example.domain.Account.{AccountCredited, AccountOpened, AuthorizeTransaction, CaptureTransaction, CreditAccount, Open, OpenAccount, TransactionAuthorized, TransactionCaptured, TransactionVoided, VoidTransaction}
import io.circe.{Decoder, Encoder}
import io.circe.generic.auto._

case class AccountId(value: String) extends AnyVal
object Account {
  sealed trait Event {
    def accountId: AccountId
  }
  case class AccountOpened(accountId: AccountId) extends Event
  case class TransactionAuthorized(accountId: AccountId, transactionId: TransactionId, amount: Amount) extends Event
  case class TransactionVoided(accountId: AccountId, transactionId: TransactionId) extends Event
  case class TransactionCaptured(accountId: AccountId, transactionId: TransactionId, amount: Amount) extends Event
  case class AccountCredited(accountId: AccountId, transactionId: TransactionId, amount: Amount) extends Event

  implicit val eventEncoder: Encoder[Event] = shapeless.cachedImplicit
  implicit val eventDecoder: Decoder[Event] = shapeless.cachedImplicit

  sealed trait Command {
    def accountId: AccountId
  }
  case class OpenAccount(accountId: AccountId) extends Command
  case class AuthorizeTransaction(accountId: AccountId, transactionId: TransactionId, amount: Amount) extends Command
  case class VoidTransaction(accountId: AccountId, transactionId: TransactionId) extends Command
  case class CaptureTransaction(accountId: AccountId, transactionId: TransactionId, amount: Amount) extends Command
  case class CreditAccount(accountId: AccountId, transactionId: TransactionId, amount: Amount) extends Command

  sealed trait Rejection
  case object AccountDoesNotExist extends Rejection
  case object InsufficientFunds extends Rejection
  case object AccountExists extends Rejection
  case object HoldNotFound extends Rejection

  sealed trait State {
    def applyEvent: Event => State = this match {
      case Initial => {
        case AccountOpened(accountId) => Open(accountId, Amount(0), Map.empty)
        case other => throw new IllegalArgumentException(s"Unexpected event $other")
      }
      case self @ Open(id, balance, holds) => {
        case e: AccountOpened => self
        case e: TransactionAuthorized => self.copy(holds = holds + (e.transactionId -> e.amount), balance = balance - e.amount)
        case e: TransactionVoided => holds.get(e.transactionId).map(holdAmount => self.copy(holds = holds - e.transactionId, balance = balance + holdAmount)).getOrElse(self)
        case e: AccountCredited => self.copy(balance = balance + e.amount)
        case e: TransactionCaptured => self.copy(holds = holds - e.transactionId)
      }
    }
  }
  object State
  case object Initial extends State
  case class Open(id: AccountId, balance: Amount, holds: Map[TransactionId, Amount]) extends State

  implicit def correlation: Correlation[Command] =
    Correlation.instance(_.accountId.value)

  implicit val entityName: AggregateName[Account] =
    AggregateName.instance("Account")

  implicit val commandContract: CommandContract.Aux[Account, Command, Rejection] =
    CommandContract.instance

  implicit val eventContract: EventContract.Aux[Account, Event] =
    EventContract.instance


  implicit def behavior: AggregateBehavior[Account, State, Command, Result[Rejection], Event] =
    new AggregateBehavior[Account, State, Command, Result[Rejection], Event] {
      override def getState(a: Account): State = a.state

      override def setState(a: Account)(state: State): Account = a.copy(state = state)

      override def handleCommand(a: Account)(command: Command): NowOrLater[CommandHandlerResult[Result[Rejection], Event]] =
        a.handleCommand(command)

      override def applyEvent(a: Account)(event: Event): Account =
        setState(a)(getState(a).applyEvent(event))
    }

  def apply(): Account = Account(Initial)

}
import Account._

case class Account(state: Account.State) {
  def handleCommand(command: Command): NowOrLater[CommandHandlerResult[Result[Rejection], Event]] = CommandHandler(state, command) {
    case Initial => {
      case OpenAccount(accountId) => accept(AccountOpened(accountId))
      case _ => reject(AccountDoesNotExist)
    }
    case Open(id, balance, holds) => {
      case c: OpenAccount =>
        reject(AccountExists)

      case AuthorizeTransaction(_, transactionId, amount) =>
        if (balance > amount) {
          accept(TransactionAuthorized(id, transactionId, amount))
        } else {
          reject(InsufficientFunds)
        }

      case VoidTransaction(_, transactionId) =>
        accept(TransactionVoided(id, transactionId))

      case CaptureTransaction(_, transactionId, clearingAmount) =>
        holds.get(transactionId) match {
          case Some(amount) =>
            accept(TransactionCaptured(id, transactionId, clearingAmount))
          case None =>
            reject(HoldNotFound)
        }

      case CreditAccount(_, transactionId, amount) =>
        accept(AccountCredited(id, transactionId, amount))
    }
  }
}