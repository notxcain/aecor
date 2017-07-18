package aecor.example.domain.account

import aecor.data.Folded.syntax._
import aecor.data.{ EventsourcedBehavior, Folded, Folder, Handler }
import aecor.example.domain.Amount
import aecor.example.domain.account.AccountAggregate.{ AccountDoesNotExist, InsufficientFunds }
import aecor.example.domain.account.AccountEvent._
import aecor.example.domain.account.EventsourcedAccountAggregate.Account
import aecor.util.Clock
import cats.Applicative
import cats.implicits._

import scala.collection.immutable._

class EventsourcedAccountAggregate[F[_]](clock: Clock[F])(implicit F: Applicative[F])
    extends AccountAggregate[Handler[F, Option[Account], AccountEvent, ?]] {

  import F._

  override def openAccount(
    accountId: AccountId
  ): Handler[F, Option[Account], AccountEvent, Either[AccountAggregate.Rejection, Unit]] =
    Handler {
      case None =>
        clock.instant.map { now =>
          Seq(AccountOpened(accountId, now)) -> ().asRight
        }
      case Some(x) => pure(Seq.empty -> AccountAggregate.AccountExists.asLeft)
    }

  override def creditAccount(
    accountId: AccountId,
    transactionId: AccountTransactionId,
    amount: Amount
  ): Handler[F, Option[Account], AccountEvent, Either[AccountAggregate.Rejection, Unit]] =
    Handler {
      case Some(account) =>
        clock.instant.map { now =>
          if (account.processedTransactions.contains(transactionId)) {
            Seq.empty -> ().asRight
          } else {
            Seq(AccountCredited(accountId, transactionId, amount, now)) -> ().asRight
          }
        }
      case None =>
        pure(Seq.empty -> AccountAggregate.AccountDoesNotExist.asLeft)
    }

  override def debitAccount(
    accountId: AccountId,
    transactionId: AccountTransactionId,
    amount: Amount
  ): Handler[F, Option[Account], AccountEvent, Either[AccountAggregate.Rejection, Unit]] =
    Handler {
      case Some(account) =>
        clock.instant.map { now =>
          if (account.processedTransactions.contains(transactionId)) {
            Seq.empty -> ().asRight
          } else {
            if (account.hasFunds(amount) || accountId == EventsourcedAccountAggregate.rootAccountId) {
              Seq(AccountDebited(accountId, transactionId, amount, now)) -> ().asRight
            } else {
              Seq.empty -> InsufficientFunds.asLeft
            }
          }
        }
      case None =>
        pure(Seq.empty -> AccountDoesNotExist.asLeft)
    }
}

object EventsourcedAccountAggregate {

  def behavior[F[_]: Applicative](
    clock: Clock[F]
  ): EventsourcedBehavior[F, AccountAggregate.AccountAggregateOp, Option[Account], AccountEvent] =
    EventsourcedBehavior(
      AccountAggregate.toFunctionK(new EventsourcedAccountAggregate[F](clock)),
      Account.folder
    )
  final val rootAccountId: AccountId = AccountId("ROOT")
  final case class Account(balance: Amount, processedTransactions: Set[AccountTransactionId]) {
    def hasFunds(amount: Amount): Boolean =
      balance >= amount
    def applyEvent(event: AccountEvent): Folded[Account] = event match {
      case AccountOpened(_, _) => impossible
      case AccountDebited(_, transactionId, amount, _) =>
        copy(
          balance = balance - amount,
          processedTransactions = processedTransactions + transactionId
        ).next
      case AccountCredited(_, transactionId, amount, _) =>
        copy(
          balance = balance + amount,
          processedTransactions = processedTransactions + transactionId
        ).next
    }
  }
  object Account {
    def fromEvent(event: AccountEvent): Folded[Account] = event match {
      case AccountOpened(_, _) => Account(Amount.zero, Set.empty).next
      case _                   => impossible
    }
    def folder: Folder[Folded, AccountEvent, Option[Account]] =
      Folder.optionInstance(fromEvent)(x => x.applyEvent)
  }
}
