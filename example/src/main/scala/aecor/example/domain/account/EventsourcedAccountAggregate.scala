package aecor.example.domain.account

import aecor.data.{ Folded, Handler }
import aecor.example.domain.Amount
import aecor.example.domain.account.EventsourcedAccountAggregate.Account
import cats.implicits._
import AccountEvent._
import aecor.aggregate.Folder
import aecor.example.domain.account.AccountAggregate.{ AccountDoesNotExist, InsufficientFunds }
import cats.Applicative
import Folded.syntax._

import scala.collection.immutable._

class EventsourcedAccountAggregate[F[_]: Applicative]
    extends AccountAggregate[Handler[F, Option[Account], Seq[AccountEvent], ?]] {

  private def handle: Handler.MkLift[F, Option[Account]] = Handler.lift[F, Option[Account]]

  override def openAccount(
    accountId: AccountId
  ): Handler[F, Option[Account], Seq[AccountEvent], Either[AccountAggregate.Rejection, Unit]] =
    handle {
      case None => Seq(AccountOpened(accountId)) -> ().asRight
      case Some(x) => Seq.empty -> AccountAggregate.AccountExists.asLeft
    }

  override def creditAccount(
    accountId: AccountId,
    transactionId: AccountTransactionId,
    amount: Amount
  ): Handler[F, Option[Account], Seq[AccountEvent], Either[AccountAggregate.Rejection, Unit]] =
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
  ): Handler[F, Option[Account], Seq[AccountEvent], Either[AccountAggregate.Rejection, Unit]] =
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

  final val rootAccountId: AccountId = AccountId("ROOT")
  final case class Account(balance: Amount, processedTransactions: Set[AccountTransactionId]) {
    def hasFunds(amount: Amount): Boolean =
      balance >= amount
    def applyEvent(event: AccountEvent): Folded[Account] = event match {
      case AccountOpened(_) => impossible
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
      case AccountOpened(_) => Account(Amount.zero, Set.empty).next
      case _ => impossible
    }
    implicit def folder: Folder[Folded, AccountEvent, Option[Account]] =
      Folder.optionInstance(fromEvent)(x => x.applyEvent)
  }
}
