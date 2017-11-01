package aecor.example.domain.account

import aecor.data.Folded.syntax._
import aecor.data._
import aecor.example.domain.Amount
import aecor.example.domain.account.Account.{ AccountDoesNotExist, InsufficientFunds }
import aecor.example.domain.account.AccountEvent._
import aecor.example.domain.account.EventsourcedAccount.AccountState
import aecor.util.Clock
import cats.Applicative
import cats.implicits._

import scala.collection.immutable._

class EventsourcedAccount[F[_]](clock: Clock[F])(implicit F: Applicative[F])
    extends Account[Action[F, Option[AccountState], AccountEvent, ?]] {

  import F._

  override def open(
    checkBalance: Boolean
  ): Action[F, Option[AccountState], AccountEvent, Either[Account.Rejection, Unit]] =
    Action {
      case None =>
        clock.instant.map { now =>
          Seq(AccountOpened(checkBalance, now)) -> ().asRight
        }
      case Some(x) => pure(Seq.empty -> Account.AccountExists.asLeft)
    }

  override def credit(
    transactionId: AccountTransactionId,
    amount: Amount
  ): Action[F, Option[AccountState], AccountEvent, Either[Account.Rejection, Unit]] =
    Action {
      case Some(account) =>
        clock.instant.map { now =>
          if (account.processedTransactions.contains(transactionId)) {
            Seq.empty -> ().asRight
          } else {
            Seq(AccountCredited(transactionId, amount, now)) -> ().asRight
          }
        }
      case None =>
        pure(Seq.empty -> Account.AccountDoesNotExist.asLeft)
    }

  override def debit(
    transactionId: AccountTransactionId,
    amount: Amount
  ): Action[F, Option[AccountState], AccountEvent, Either[Account.Rejection, Unit]] =
    Action {
      case Some(account) =>
        clock.instant.map { now =>
          if (account.processedTransactions.contains(transactionId)) {
            Seq.empty -> ().asRight
          } else {
            if (account.hasFunds(amount)) {
              Seq(AccountDebited(transactionId, amount, now)) -> ().asRight
            } else {
              Seq.empty -> InsufficientFunds.asLeft
            }
          }
        }
      case None =>
        pure(Seq.empty -> AccountDoesNotExist.asLeft)
    }
}

object EventsourcedAccount {

  def behavior[F[_]: Applicative](
    clock: Clock[F]
  ): EventsourcedBehavior[F, Account.AccountOp, Option[AccountState], AccountEvent] =
    EventsourcedBehavior.optional(
      AccountState.fromEvent,
      Account.toFunctionK(new EventsourcedAccount[F](clock)),
      _.applyEvent(_)
    )
  final val rootAccountId: AccountId = AccountId("ROOT")
  final case class AccountState(balance: Amount,
                                processedTransactions: Set[AccountTransactionId],
                                checkBalance: Boolean) {
    def hasFunds(amount: Amount): Boolean =
      !checkBalance || balance >= amount
    def applyEvent(event: AccountEvent): Folded[AccountState] = event match {
      case AccountOpened(_, _) => impossible
      case AccountDebited(transactionId, amount, _) =>
        copy(
          balance = balance - amount,
          processedTransactions = processedTransactions + transactionId
        ).next
      case AccountCredited(transactionId, amount, _) =>
        copy(
          balance = balance + amount,
          processedTransactions = processedTransactions + transactionId
        ).next
    }
  }
  object AccountState {
    def fromEvent(event: AccountEvent): Folded[AccountState] = event match {
      case AccountOpened(checkBalance, _) => AccountState(Amount.zero, Set.empty, checkBalance).next
      case _                              => impossible
    }
  }
}
