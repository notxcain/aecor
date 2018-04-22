package aecor.example.domain

import aecor.Has
import aecor.Has.syntax._
import aecor.example.domain.TransactionProcess.TransactionProcessFailure
import aecor.example.domain.account.{
  Account,
  AccountId,
  AccountTransactionId,
  AccountTransactionKind
}
import aecor.example.domain.transaction.TransactionAggregate.TransactionInfo
import aecor.example.domain.transaction._
import cats.implicits._
import cats.{ Monad, MonadError }

class TransactionProcess[F[_]: Monad](transactions: TransactionId => TransactionAggregate[F],
                                      accounts: AccountId => Account[F],
                                      failure: TransactionProcessFailure[F]) {
  def process[A: Has[TransactionId, ?]: Has[TransactionEvent, ?]](a: A): F[Unit] = {
    val transactionId = a.get[TransactionId]
    a.get[TransactionEvent] match {
      case TransactionEvent.TransactionCreated(From(from), _, amount) =>
        for {
          out <- accounts(from)
                  .debit(AccountTransactionId(transactionId, AccountTransactionKind.Normal), amount)
          _ <- out match {
                case Left(rejection) =>
                  transactions(transactionId).fail(rejection.toString)
                case Right(_) =>
                  transactions(transactionId).authorize
              }
        } yield ()
      case TransactionEvent.TransactionAuthorized =>
        for {
          txn <- transactions(transactionId).getInfo.flatMap {
                  case Some(x) => x.pure[F]
                  case None =>
                    failure.failProcess[TransactionInfo](s"Transaction [$transactionId] not found")
                }
          creditResult <- accounts(txn.toAccountId.value).credit(
                           AccountTransactionId(transactionId, AccountTransactionKind.Normal),
                           txn.amount
                         )
          _ <- creditResult match {
                case Left(rejection) =>
                  accounts(txn.fromAccountId.value).debit(
                    AccountTransactionId(transactionId, AccountTransactionKind.Revert),
                    txn.amount
                  ) >> transactions(transactionId).fail(rejection.toString)
                case Right(_) =>
                  transactions(transactionId).succeed
              }
        } yield ()
      case other =>
        ().pure[F]
    }
  }
}

object TransactionProcess {

  trait TransactionProcessFailure[F[_]] {
    def failProcess[A](reason: String): F[A]
  }

  object TransactionProcessFailure {
    def withMonadError[F[_]](implicit F: MonadError[F, Throwable]): TransactionProcessFailure[F] =
      new TransactionProcessFailure[F] {
        override def failProcess[A](reason: String): F[A] =
          F.raiseError(new RuntimeException(reason))
      }
  }

  def apply[F[_]: Monad](transactions: TransactionId => TransactionAggregate[F],
                         accounts: AccountId => Account[F],
                         failure: TransactionProcessFailure[F]): TransactionProcess[F] =
    new TransactionProcess(transactions, accounts, failure)
}
