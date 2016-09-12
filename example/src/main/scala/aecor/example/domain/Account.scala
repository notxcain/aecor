package aecor.example.domain

import java.time.Clock

import aecor.core.aggregate.AggregateBehavior.syntax._
import aecor.core.aggregate._
import aecor.core.message.Correlation
import aecor.example.domain.Account.State.{Initial, Open}
import aecor.example.domain.Account.{AccountCredited, AccountOpened, AuthorizeTransaction, CaptureTransaction, CreditAccount, Event, OpenAccount, State, TransactionAuthorized, TransactionCaptured, TransactionVoided, VoidTransaction}
import aecor.util.function._
import akka.Done
import cats.data.Xor
import io.circe.{Decoder, Encoder}
import io.circe.generic.auto._
import scala.collection.immutable.Seq
import scala.concurrent.Future

case class AccountId(value: String) extends AnyVal

object Account {

  object Command {
    implicit def correlation: Correlation[Command[_]] =
      Correlation.instance(_.accountId.value)
  }

  sealed trait Command[R] {
    def accountId: AccountId
  }

  type CommandResult[+R] = Xor[R, Done]

  case class OpenAccount(accountId: AccountId) extends Command[CommandResult[Rejection]]

  case class AuthorizeTransaction(accountId: AccountId, transactionId: TransactionId, amount: Amount) extends Command[CommandResult[AuthorizeTransactionRejection]]

  case class VoidTransaction(accountId: AccountId, transactionId: TransactionId) extends Command[CommandResult[Rejection]]

  case class CaptureTransaction(accountId: AccountId, transactionId: TransactionId, amount: Amount) extends Command[CommandResult[Rejection]]

  case class CreditAccount(accountId: AccountId, transactionId: TransactionId, amount: Amount) extends Command[CommandResult[Rejection]]

  sealed trait Rejection

  sealed trait AuthorizeTransactionRejection

  case object DuplicateTransaction extends AuthorizeTransactionRejection

  case object AccountDoesNotExist extends Rejection with AuthorizeTransactionRejection

  case object InsufficientFunds extends Rejection with AuthorizeTransactionRejection

  case object AccountExists extends Rejection

  case object HoldNotFound extends Rejection

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

  sealed trait State {
    def applyEvent(event: Event): State =
      handle(this, event) {
        case Initial => {
          case AccountOpened(accountId) => Open(accountId, Amount(0), Map.empty, Set.empty)
          case other => throw new IllegalArgumentException(s"Unexpected event $other")
        }
        case self@Open(id, balance, holds, transactions) => {
          case e: AccountOpened => self
          case e: TransactionAuthorized => self.copy(holds = holds + (e.transactionId -> e.amount), balance = balance - e.amount, transactions = transactions + e.transactionId)
          case e: TransactionVoided => holds.get(e.transactionId).map(holdAmount => self.copy(holds = holds - e.transactionId, balance = balance + holdAmount)).getOrElse(self)
          case e: AccountCredited => self.copy(balance = balance + e.amount)
          case e: TransactionCaptured => self.copy(holds = holds - e.transactionId)
        }
      }
  }
  object State {
    case object Initial extends State
    case class Open(id: AccountId, balance: Amount, holds: Map[TransactionId, Amount], transactions: Set[TransactionId]) extends State
  }

  implicit val entityName: AggregateName[Account] =
    AggregateName.instance("Account")

  implicit object behavior extends AggregateBehavior[Account] {
    override type Command[X] = Account.Command[X]
    override type Event = Account.Event
    override type State = Account.State

    override def handleCommand[Response](a: Account)(state: State, command: Command[Response]): Future[(Response, Seq[Event])] =
      a.handleCommand(command, state)

    override def applyEvent(state: State, event: Event): State =
      state.applyEvent(event)

    override def init: State = Initial
  }

  def apply(): Account = new Account(Clock.systemDefaultZone())

}

import aecor.example.domain.Account._

class Account(val clock: Clock) {
  def handleCommand[R](command: Command[R], state: State): Future[(R, Seq[Event])] = Future.successful {
    handle(command, state) {
      case OpenAccount(accountId) => {
        case Initial =>
          accept(AccountOpened(accountId))
        case _ =>
          reject(AccountExists)
      }
      case AuthorizeTransaction(_, transactionId, amount) => {
        case Initial =>
          reject(AccountDoesNotExist)
        case Open(id, balance, holds, transactions) =>
          if (transactions.contains(transactionId)) {
            reject(DuplicateTransaction)
          } else if (balance > amount) {
            accept(TransactionAuthorized(id, transactionId, amount))
          } else {
            reject(InsufficientFunds)
          }
      }
      case VoidTransaction(_, transactionId) => {
        case Initial =>
          reject(AccountDoesNotExist)
        case Open(id, _, _, _) =>
          accept(TransactionVoided(id, transactionId))
      }
      case CaptureTransaction(_, transactionId, clearingAmount) => {
        case Initial =>
          reject(AccountDoesNotExist)
        case Open(id, _, holds, _) =>
          holds.get(transactionId) match {
            case Some(amount) =>
              accept(TransactionCaptured(id, transactionId, clearingAmount))
            case None =>
              reject(HoldNotFound)
          }
      }
      case CreditAccount(_, transactionId, amount) => {
        case Initial =>
          reject(AccountDoesNotExist)
        case state: Open =>
          accept(AccountCredited(state.id, transactionId, amount))
      }
    }
  }
}