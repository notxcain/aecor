package aecor.example.domain.account

import java.time.Instant

import aecor.data.Folded.syntax._
import aecor.data.{ EventsourcedBehavior, Folded, Folder, Handler }
import aecor.example.domain.Amount
import aecor.example.domain.account.AccountAggregate.{ AccountDoesNotExist, InsufficientFunds }
import aecor.example.domain.account.AccountEvent._
import aecor.example.domain.account.EventsourcedAccountAggregate.Account
import cats.Applicative
import cats.implicits._

import scala.collection.immutable._

class EventsourcedAccountAggregate[F[_]: Applicative]
    extends AccountAggregate[Handler[F, Option[Account], AccountEvent, ?]] {

  private def handle = Handler.lift[F, Option[Account]]

  override def openAccount(
    accountId: AccountId
  ): Handler[F, Option[Account], AccountEvent, Either[AccountAggregate.Rejection, Unit]] =
    handle {
      case None    => Seq(AccountOpened(accountId, 0)) -> ().asRight
      case Some(x) => Seq.empty -> AccountAggregate.AccountExists.asLeft
    }

  override def creditAccount(
    accountId: AccountId,
    transactionId: AccountTransactionId,
    amount: Amount
  ): Handler[F, Option[Account], AccountEvent, Either[AccountAggregate.Rejection, Unit]] =
    handle {
      case Some(account) =>
        if (account.processedTransactions.contains(transactionId)) {
          Seq.empty -> ().asRight
        } else {
          Seq(AccountCredited(accountId, transactionId, amount)) -> ().asRight
        }
      case None =>
        Seq.empty -> AccountAggregate.AccountDoesNotExist.asLeft
    }

  override def debitAccount(
    accountId: AccountId,
    transactionId: AccountTransactionId,
    amount: Amount
  ): Handler[F, Option[Account], AccountEvent, Either[AccountAggregate.Rejection, Unit]] =
    handle {
      case Some(account) =>
        if (account.processedTransactions.contains(transactionId)) {
          Seq.empty -> ().asRight
        } else {
          if (account.hasFunds(amount) || accountId == EventsourcedAccountAggregate.rootAccountId) {
            Seq(AccountDebited(accountId, transactionId, amount)) -> ().asRight
          } else {
            Seq.empty -> InsufficientFunds.asLeft
          }
        }
      case None =>
        Seq.empty -> AccountDoesNotExist.asLeft
    }
}

object EventsourcedAccountAggregate {

  final case class EventsourcedAccountAggregateState(value: Option[Account])
      extends AccountAggregate[Lambda[a => (Instant => (Seq[AccountEvent], a))]] {
    override def openAccount(
      accountId: AccountId
    ): (Instant) => (Seq[AccountEvent], Either[AccountAggregate.Rejection, Unit]) = value match {
      case None =>
        ts =>
          Seq(AccountOpened(accountId, ts.getEpochSecond)) -> ().asRight
      case Some(x) =>
        _ =>
          Seq.empty -> AccountAggregate.AccountExists.asLeft
    }

    override def creditAccount(
      accountId: AccountId,
      transactionId: AccountTransactionId,
      amount: Amount
    ): (Instant) => (Seq[AccountEvent], Either[AccountAggregate.Rejection, Unit]) = ???

    override def debitAccount(
      accountId: AccountId,
      transactionId: AccountTransactionId,
      amount: Amount
    ): (Instant) => (Seq[AccountEvent], Either[AccountAggregate.Rejection, Unit]) = ???
  }

  def behavior[F[_]: Applicative]
    : EventsourcedBehavior[F, AccountAggregate.AccountAggregateOp, Option[Account], AccountEvent] =
    EventsourcedBehavior(
      AccountAggregate.toFunctionK(new EventsourcedAccountAggregate[F]),
      Account.folder
    )
  final val rootAccountId: AccountId = AccountId("ROOT")
  final case class Account(balance: Amount, processedTransactions: Set[AccountTransactionId]) {
    def hasFunds(amount: Amount): Boolean =
      balance >= amount
    def applyEvent(event: AccountEvent): Folded[Account] = event match {
      case AccountOpened(_, _) => impossible
      case AccountDebited(_, transactionId, amount) =>
        copy(
          balance = balance - amount,
          processedTransactions = processedTransactions + transactionId
        ).next
      case AccountCredited(_, transactionId, amount) =>
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
