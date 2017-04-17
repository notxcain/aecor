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

  def apply[F[_]: Monad](
    implicit transactionAggregate: TransactionAggregate[F],
    accountAggregate: AccountAggregate[F],
    transactionProcessFailure: TransactionProcessFailure[F]
  ): Input => F[Unit] = {
    import accountAggregate._
    import transactionProcessFailure._
    import transactionAggregate._
    {
      case TransactionEvent.TransactionCreated(transactionId, from, to, amount) =>
        for {
          out <- debitAccount(
                  from.value,
                  AccountTransactionId(transactionId, AccountTransactionKind.Normal),
                  amount
                )
          _ <- out match {
                case Left(rejection) =>
                  failTransaction(transactionId, rejection.toString)
                case Right(_) =>
                  authorizeTransaction(transactionId)
              }
        } yield ()
      case TransactionEvent.TransactionAuthorized(transactionId) =>
        for {
          txn <- getTransactionInfo(transactionId).flatMap {
                  case Some(x) => x.pure[F]
                  case None =>
                    failProcess[TransactionInfo](s"Transaction [$transactionId] not found")
                }
          creditResult <- creditAccount(
                           txn.toAccountId.value,
                           AccountTransactionId(transactionId, AccountTransactionKind.Normal),
                           txn.amount
                         )
          _ <- creditResult match {
                case Left(rejection) =>
                  debitAccount(
                    txn.fromAccountId.value,
                    AccountTransactionId(txn.transactionId, AccountTransactionKind.Revert),
                    txn.amount
                  ) >> failTransaction(transactionId, rejection.toString)
                case Right(_) =>
                  succeedTransaction(transactionId)
              }
        } yield ()
      case other =>
        ().pure[F]
    }
  }
}
