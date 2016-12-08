package aecor.example.domain

import java.time.Clock

import aecor.core.aggregate._
import aecor.example.domain.AccountAggregate.Account
import aecor.example.domain.AccountAggregateEvent._
import aecor.util.function._

import aecor.core.aggregate.AggregateBehavior.syntax._

import scala.collection.immutable.Seq

case class AccountId(value: String) extends AnyVal

object AccountAggregate {

  case class State(value: Option[Account]) extends AnyVal {
    def applyEvent(event: AccountAggregateEvent): State =
      handle(value, event) {
        case None => {
          case AccountOpened(accountId) =>
            State(Some(Account(accountId, Amount(0), Map.empty, Set.empty)))
          case other =>
            throw new IllegalArgumentException(s"Unexpected event $other")
        }
        case Some(Account(id, balance, holds, transactions)) => {
          case e: AccountOpened => this
          case e: TransactionAuthorized =>
            transform(
              _.copy(holds = holds + (e.transactionId -> e.amount),
                     balance = balance - e.amount,
                     transactions = transactions + e.transactionId))
          case e: TransactionVoided =>
            holds
              .get(e.transactionId)
              .map(holdAmount =>
                transform(_.copy(holds = holds - e.transactionId,
                                 balance = balance + holdAmount)))
              .getOrElse(this)
          case e: AccountCredited =>
            transform(_.copy(balance = balance + e.amount))
          case e: TransactionCaptured =>
            transform(_.copy(holds = holds - e.transactionId))
        }
      }

    def transform(f: Account => Account): State =
      value.map(x => copy(value = Some(f(x)))).getOrElse(this)
  }

  case class Account(id: AccountId,
                     balance: Amount,
                     holds: Map[TransactionId, Amount],
                     transactions: Set[TransactionId])

  implicit val entityName: AggregateName[AccountAggregate] =
    AggregateName.instance("Account")

  implicit object behavior extends AggregateBehavior[AccountAggregate] {
    override type Command[X] = AccountAggregateOp[X]
    override type Event = AccountAggregateEvent
    override type State = AccountAggregate.State

    override def handleCommand[Response](a: AccountAggregate)(
        state: State,
        command: Command[Response]): (Response, Seq[Event]) =
      a.handleCommand(command, state.value)

    override def applyEvent(state: State, event: Event): State =
      state.applyEvent(event)

    override def init: State = State(None)
  }
}

import aecor.example.domain.AccountAggregateOp._

case class AccountAggregate(clock: Clock) {

  def handleCommand[R](
      command: AccountAggregateOp[R],
      state: Option[Account]): (R, Seq[AccountAggregateEvent]) =
    command match {
      case OpenAccount(accountId) =>
        state match {
          case None =>
            accept(AccountOpened(accountId))
          case _ =>
            reject(AccountExists)
        }

      case AuthorizeTransaction(_, transactionId, amount) =>
        state match {
          case None =>
            reject(AccountDoesNotExist)
          case Some(Account(id, balance, holds, transactions)) =>
            if (transactions.contains(transactionId)) {
              reject(DuplicateTransaction)
            } else if (balance > amount) {
              accept(TransactionAuthorized(id, transactionId, amount))
            } else {
              reject(InsufficientFunds)
            }
        }
      case VoidTransaction(_, transactionId) =>
        state match {
          case None =>
            reject(AccountDoesNotExist)
          case Some(Account(id, balance, holds, transactions)) =>
            accept(TransactionVoided(id, transactionId))
        }
      case CaptureTransaction(_, transactionId, clearingAmount) =>
        state match {
          case None =>
            reject(AccountDoesNotExist)
          case Some(Account(id, balance, holds, transactions)) =>
            holds.get(transactionId) match {
              case Some(amount) =>
                accept(TransactionCaptured(id, transactionId, clearingAmount))
              case None =>
                reject(HoldNotFound)
            }
        }
      case CreditAccount(_, transactionId, amount) =>
        state match {
          case None =>
            reject(AccountDoesNotExist)
          case Some(Account(id, balance, holds, transactions)) =>
            accept(AccountCredited(id, transactionId, amount))
        }
    }
}
