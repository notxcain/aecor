package aecor.example.domain

import aecor.example.domain.account.{
  AccountAggregate,
  AccountTransactionId,
  AccountTransactionKind
}
import aecor.example.domain.transaction.TransactionAggregate.TransactionInfo
import aecor.example.domain.transaction.{ TransactionAggregate, TransactionEvent }
import cats.implicits._
import cats.{ Monad, MonadError }
import io.aecor.liberator.macros.{ algebra, term }

object TransactionProcess {
  type Input = TransactionEvent

  @algebra
  @term
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

  def apply[F[_]: Monad](transaction: TransactionAggregate[F],
                         account: AccountAggregate[F],
                         failure: TransactionProcessFailure[F]): Input => F[Unit] = {
    case TransactionEvent.TransactionCreated(transactionId, from, to, amount, _) =>
      for {
        out <- account.debitAccount(
                from.value,
                AccountTransactionId(transactionId, AccountTransactionKind.Normal),
                amount
              )
        _ <- out match {
              case Left(rejection) =>
                transaction.failTransaction(transactionId, rejection.toString)
              case Right(_) =>
                transaction.authorizeTransaction(transactionId)
            }
      } yield ()
    case TransactionEvent.TransactionAuthorized(transactionId, _) =>
      for {
        txn <- transaction.getTransactionInfo(transactionId).flatMap {
                case Some(x) => x.pure[F]
                case None =>
                  failure.failProcess[TransactionInfo](s"Transaction [$transactionId] not found")
              }
        creditResult <- account.creditAccount(
                         txn.toAccountId.value,
                         AccountTransactionId(transactionId, AccountTransactionKind.Normal),
                         txn.amount
                       )
        _ <- creditResult match {
              case Left(rejection) =>
                account.debitAccount(
                  txn.fromAccountId.value,
                  AccountTransactionId(txn.transactionId, AccountTransactionKind.Revert),
                  txn.amount
                ) >> transaction.failTransaction(transactionId, rejection.toString)
              case Right(_) =>
                transaction.succeedTransaction(transactionId)
            }
      } yield ()
    case other =>
      ().pure[F]
  }
}
