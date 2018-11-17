package aecor.example.process

import aecor.Has
import aecor.Has.syntax._
import aecor.example.account.{AccountTransactionId, AccountTransactionKind, Accounts}
import aecor.example.transaction.Algebra.TransactionInfo
import aecor.example.transaction.transaction.Transactions
import aecor.example.transaction.{From, TransactionEvent, TransactionId}
import cats.MonadError
import cats.implicits._


final class TransactionProcessor[F[_]](transactions: Transactions[F], accounts: Accounts[F])(implicit F: MonadError[F, Throwable]) {
  def process[A: Has[?, TransactionId]: Has[?, TransactionEvent]](a: A): F[Unit] = {
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
            case Right(x) => x.pure[F]
            case Left(r) => F.raiseError[TransactionInfo](TransactionProcessor.Failure(r))
          }
          creditResult <- accounts(txn.toAccountId.value).credit(
                           AccountTransactionId(transactionId, AccountTransactionKind.Normal),
                           txn.amount
                         )
          _ <- creditResult match {
                case Left(rejection) =>
                  accounts(txn.fromAccountId.value).credit(
                    AccountTransactionId(transactionId, AccountTransactionKind.Revert),
                    txn.amount
                  ) >> transactions(transactionId).fail(rejection.toString)
                case Right(_) =>
                  transactions(transactionId).succeed
              }
        } yield ()
      case _ =>
        ().pure[F]
    }
  }


}

object TransactionProcessor {

  final case class Failure(description: String) extends RuntimeException(description)

  def apply[F[_]: MonadError[?[_], Throwable]](transactions: Transactions[F], accounts: Accounts[F]): TransactionProcessor[F] =
    new TransactionProcessor(transactions, accounts)
}
