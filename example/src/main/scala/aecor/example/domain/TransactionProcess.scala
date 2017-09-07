package aecor.example.domain

import aecor.data.Identified
import aecor.example.domain.account.{
  AccountAggregate,
  AccountId,
  AccountTransactionId,
  AccountTransactionKind
}
import aecor.example.domain.transaction.TransactionAggregate.TransactionInfo
import aecor.example.domain.transaction.{ TransactionAggregate, TransactionEvent, TransactionId }
import cats.implicits._
import cats.{ Monad, MonadError }
import io.aecor.liberator.macros.algebra

object TransactionProcess {
  type Input = (Identified[TransactionId, TransactionEvent])

  @algebra
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
                         accounts: AccountId => AccountAggregate[F],
                         failure: TransactionProcessFailure[F]): Input => F[Unit] = {
    case Identified(transactionId, TransactionEvent.TransactionCreated(from, to, amount, _)) =>
      for {
        out <- accounts(from.value).debitAccount(
                AccountTransactionId(transactionId, AccountTransactionKind.Normal),
                amount
              )
        _ <- out match {
              case Left(rejection) =>
                transactions(transactionId).failTransaction(rejection.toString)
              case Right(_) =>
                transactions(transactionId).authorizeTransaction
            }
      } yield ()
    case Identified(transactionId, TransactionEvent.TransactionAuthorized(_)) =>
      for {
        txn <- transactions(transactionId).getTransactionInfo.flatMap {
                case Some(x) => x.pure[F]
                case None =>
                  failure.failProcess[TransactionInfo](s"Transaction [$transactionId] not found")
              }
        creditResult <- accounts(txn.toAccountId.value).creditAccount(
                         AccountTransactionId(transactionId, AccountTransactionKind.Normal),
                         txn.amount
                       )
        _ <- creditResult match {
              case Left(rejection) =>
                accounts(txn.fromAccountId.value).debitAccount(
                  AccountTransactionId(transactionId, AccountTransactionKind.Revert),
                  txn.amount
                ) >> transactions(transactionId).failTransaction(rejection.toString)
              case Right(_) =>
                transactions(transactionId).succeedTransaction
            }
      } yield ()
    case other =>
      ().pure[F]
  }
}
