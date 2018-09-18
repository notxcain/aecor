package aecor.example.domain

import aecor.Has
import aecor.Has.syntax._
import aecor.example.domain.account.{Account, AccountId, AccountTransactionId, AccountTransactionKind}
import aecor.example.domain.transaction.TransactionAggregate.TransactionInfo
import aecor.example.domain.transaction._
import cats.data.EitherT
import cats.implicits._
import cats.{MonadError, ~>}
import io.aecor.liberator.FunctorK


class TransactionProcess[F[_]](transactions: Entity[TransactionId, TransactionAggregate, F, String],
                                      accounts: Entity[AccountId, Account, F, Account.Rejection])(implicit F: MonadError[F, Throwable]) {
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
            case Left(r) => F.raiseError[TransactionInfo](TransactionProcess.Failure(r))
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

  final case class Failure(description: String) extends RuntimeException(description)

  def apply[F[_]: MonadError[?[_], Throwable]](transactions: TransactionId => TransactionAggregate[F],
                         accounts: AccountId => Account[F]): TransactionProcess[F] =
    new TransactionProcess(transactions, accounts, failure)
}
