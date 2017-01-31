package aecor.example.domain

import java.time.Clock

import aecor.aggregate.{ Correlation, CorrelationIdF, Folder }
import aecor.data.Folded.syntax._
import aecor.data.{ Folded, Handler }
import aecor.example.domain.AccountAggregateEvent._
import aecor.example.domain.AccountAggregateOp._
import akka.Done
import cats.arrow.FunctionK
import cats.~>

import scala.collection.immutable.Seq

case class AccountId(value: String) extends AnyVal

object AccountAggregate {

  def correlation: Correlation[AccountAggregateOp] = {
    def mk[A](fa: AccountAggregateOp[A]): CorrelationIdF[A] =
      fa.accountId.value
    FunctionK.lift(mk _)
  }

  def applyEvent(state: Option[Account], event: AccountAggregateEvent): Folded[Option[Account]] =
    state match {
      case None =>
        event match {
          case AccountOpened(accountId) =>
            Some(Account(accountId, Amount(0), Map.empty, Set.empty)).next
          case other =>
            impossible
        }
      case Some(Account(id, balance, holds, transactions)) =>
        event match {
          case e: AccountOpened => impossible
          case e: TransactionAuthorized =>
            state
              .map(
                _.copy(
                  holds = holds + (e.transactionId -> e.amount),
                  balance = balance - e.amount,
                  transactions = transactions + e.transactionId
                )
              )
              .next
          case e: TransactionVoided =>
            holds
              .get(e.transactionId)
              .map(
                holdAmount =>
                  state
                    .map(_.copy(holds = holds - e.transactionId, balance = balance + holdAmount))
              )
              .getOrElse(state)
              .next
          case e: AccountCredited =>
            state.map(_.copy(balance = balance + e.amount)).next
          case e: TransactionCaptured =>
            state.map(_.copy(holds = holds - e.transactionId)).next
        }
    }

  case class Account(id: AccountId,
                     balance: Amount,
                     holds: Map[TransactionId, Amount],
                     transactions: Set[TransactionId])

  object Account {
    implicit val folder: Folder[Folded, AccountAggregateEvent, Option[Account]] =
      Folder.instanceFor[AccountAggregateEvent](Option.empty[Account]) {
        case None => {
          case AccountOpened(accountId) =>
            Some(Account(accountId, Amount(0), Map.empty, Set.empty)).next
          case other =>
            impossible
        }
        case state @ Some(Account(id, balance, holds, transactions)) => {
          case e: AccountOpened => impossible
          case e: TransactionAuthorized =>
            state
              .map(
                _.copy(
                  holds = holds + (e.transactionId -> e.amount),
                  balance = balance - e.amount,
                  transactions = transactions + e.transactionId
                )
              )
              .next
          case e: TransactionVoided =>
            holds
              .get(e.transactionId)
              .map(
                holdAmount =>
                  state
                    .map(_.copy(holds = holds - e.transactionId, balance = balance + holdAmount))
              )
              .getOrElse(state)
              .next
          case e: AccountCredited =>
            state.map(_.copy(balance = balance + e.amount)).next
          case e: TransactionCaptured =>
            state.map(_.copy(holds = holds - e.transactionId)).next
        }
      }
  }

  val entityName: String = "Account"

  def commandHandler(clock: Clock) =
    new (AccountAggregateOp ~> Handler[Option[AccountAggregate.Account], AccountAggregateEvent, ?]) {
      def accept[R, E](events: E*): (Seq[E], Either[R, Done]) =
        (events.toVector, Right(Done))

      def reject[R, E](rejection: R): (Seq[E], Either[R, Done]) =
        (Seq.empty, Left(rejection))

      override def apply[A](fa: AccountAggregateOp[A]) =
        fa match {
          case OpenAccount(accountId) =>
            Handler {
              case None =>
                accept(AccountOpened(accountId))
              case _ =>
                reject(AccountExists)
            }

          case AuthorizeTransaction(_, transactionId, amount) =>
            Handler {
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
            Handler {
              case None =>
                reject(AccountDoesNotExist)
              case Some(Account(id, balance, holds, transactions)) =>
                accept(TransactionVoided(id, transactionId))
            }
          case CaptureTransaction(_, transactionId, clearingAmount) =>
            Handler {
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
            Handler {
              case None =>
                reject(AccountDoesNotExist)
              case Some(Account(id, balance, holds, transactions)) =>
                accept(AccountCredited(id, transactionId, amount))
            }
        }
    }
}
