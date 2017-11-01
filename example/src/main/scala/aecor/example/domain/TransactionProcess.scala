package aecor.example.domain

import aecor.data.Identified
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

object TransactionProcess {
  type Input = (Identified[TransactionId, TransactionEvent])

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
                         failure: TransactionProcessFailure[F]): Input => F[Unit] = {
    case Identified(transactionId, TransactionEvent.TransactionCreated(From(from), _, amount)) =>
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
    case Identified(transactionId, TransactionEvent.TransactionAuthorized) =>
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
