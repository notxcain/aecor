package aecor.example.account

import aecor.MonadActionReject
import aecor.data.Folded.syntax._
import aecor.data._
import aecor.example.account.AccountEvent._
import aecor.example.account.EventsourcedAlgebra.AccountState
import aecor.example.account.Rejection._
import aecor.example.common.Amount
import cats.Monad
import cats.implicits._

final class EventsourcedAlgebra[F[_]](
  implicit F: MonadActionReject[F, Option[AccountState], AccountEvent, Rejection]
) extends Algebra[F] {

  import F._

  override def open(checkBalance: Boolean): F[Unit] =
    read.flatMap {
      case None =>
        append(AccountOpened(checkBalance))
      case Some(_) =>
        ().pure[F]
    }

  override def credit(transactionId: AccountTransactionId, amount: Amount): F[Unit] =
    read.flatMap {
      case Some(account) =>
        if (account.hasProcessedTransaction(transactionId)) {
          ().pure[F]
        } else {
          append(AccountCredited(transactionId, amount))
        }
      case None =>
        reject(AccountDoesNotExist)
    }

  override def debit(transactionId: AccountTransactionId, amount: Amount): F[Unit] =
    read.flatMap {
      case Some(account) =>
        if (account.hasProcessedTransaction(transactionId)) {
          ().pure[F]
        } else {
          if (account.hasFunds(amount)) {
            append(AccountDebited(transactionId, amount))
          } else {
            reject(InsufficientFunds)
          }
        }
      case None =>
        reject(AccountDoesNotExist)
    }
}

object EventsourcedAlgebra {

  def apply[F[_]](
    implicit F: MonadActionReject[F, Option[AccountState], AccountEvent, Rejection]
  ): Algebra[F] = new EventsourcedAlgebra

  def behavior[F[_]: Monad]: EventsourcedBehavior[EitherK[Algebra, Rejection, ?[_]], F, Option[
    AccountState
  ], AccountEvent] =
    EventsourcedBehavior
      .optionalRejectable(EventsourcedAlgebra.apply, AccountState.fromEvent, _.applyEvent(_))

  val tagging: Tagging[AccountId] = Tagging.const[AccountId](EventTag("Account"))

  final val rootAccountId: AccountId = AccountId("ROOT")
  final case class AccountState(balance: Amount,
                                processedTransactions: Set[AccountTransactionId],
                                checkBalance: Boolean) {
    def hasProcessedTransaction(transactionId: AccountTransactionId): Boolean =
      processedTransactions.contains(transactionId)
    def hasFunds(amount: Amount): Boolean =
      !checkBalance || balance >= amount
    def applyEvent(event: AccountEvent): Folded[AccountState] = event match {
      case AccountOpened(_) => impossible
      case AccountDebited(transactionId, amount) =>
        copy(
          balance = balance - amount,
          processedTransactions = processedTransactions + transactionId
        ).next
      case AccountCredited(transactionId, amount) =>
        copy(
          balance = balance + amount,
          processedTransactions = processedTransactions + transactionId
        ).next
    }
  }
  object AccountState {
    def fromEvent(event: AccountEvent): Folded[AccountState] = event match {
      case AccountOpened(checkBalance) => AccountState(Amount.zero, Set.empty, checkBalance).next
      case _                           => impossible
    }
  }
}
