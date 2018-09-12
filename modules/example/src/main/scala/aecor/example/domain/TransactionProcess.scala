package aecor.example.domain

import aecor.Has
import aecor.Has.syntax._
import aecor.example.domain.TransactionProcess.RaiseError
import aecor.example.domain.account.{Account, AccountId, AccountTransactionId, AccountTransactionKind}
import aecor.example.domain.transaction.TransactionAggregate.TransactionInfo
import aecor.example.domain.transaction._
import cats.data.EitherT
import cats.implicits._
import cats.{Monad, MonadError}

class TransactionProcess[F[_]: Monad](transactions: TransactionId => TransactionAggregate[EitherT[F, String, ?]],
                                      accounts: AccountId => Account[EitherT[F, Account.Rejection, ?]],
                                      failure: RaiseError[F]) {
  def process[A: Has[?, TransactionId]: Has[?, TransactionEvent]](a: A): F[Unit] = {
    val transactionId = a.get[TransactionId]
    a.get[TransactionEvent] match {
      case TransactionEvent.TransactionCreated(From(from), _, amount) =>
        for {
          out <- accounts(from)
                  .debit(AccountTransactionId(transactionId, AccountTransactionKind.Normal), amount).value
          _ <- out match {
                case Left(rejection) =>
                  transactions(transactionId).fail(rejection.toString)
                case Right(_) =>
                  transactions(transactionId).authorize
              }
        } yield ()
      case TransactionEvent.TransactionAuthorized =>
        for {
          txn <- transactions(transactionId).getInfo.leftSemiflatMap(failure.raiseError[Unit])
          creditResult <- accounts(txn.toAccountId.value).credit(
                           AccountTransactionId(transactionId, AccountTransactionKind.Normal),
                           txn.amount
                         )
          _ <- creditResult match {
                case Left(rejection) =>
                  accounts(txn.fromAccountId.value).debit(
                    AccountTransactionId(transactionId, AccountTransactionKind.Revert),
                    txn.amount
                  ).value >> transactions(transactionId).fail(rejection.toString).value
                case Right(_) =>
                  transactions(transactionId).succeed.value
              }
        } yield ()
      case other =>
        ().pure[F]
    }
  }
}

object TransactionProcess {

  trait RaiseError[F[_]] {
    def raiseError[A](reason: String): F[A]
  }

  object RaiseError {
    def withMonadError[F[_]](implicit F: MonadError[F, Throwable]): RaiseError[F] =
      new RaiseError[F] {
        override def raiseError[A](reason: String): F[A] =
          F.raiseError(new RuntimeException(reason))
      }
  }

  def apply[F[_]: Monad](transactions: TransactionId => TransactionAggregate[F],
                         accounts: AccountId => Account[F],
                         failure: RaiseError[F]): TransactionProcess[F] =
    new TransactionProcess(transactions, accounts, failure)
}
