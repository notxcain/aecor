package aecor.example.domain.transaction

import aecor.data.Folded.syntax._
import aecor.data.{ EventsourcedBehavior, Folded, Folder, Handler }
import aecor.example.domain.Amount
import aecor.example.domain.account.AccountId
import aecor.example.domain.transaction.EventsourcedTransactionAggregate.Transaction
import aecor.example.domain.transaction.EventsourcedTransactionAggregate.TransactionStatus.{
  Authorized,
  Failed,
  Requested,
  Succeeded
}
import aecor.example.domain.transaction.TransactionAggregate._
import aecor.example.domain.transaction.TransactionEvent.{
  TransactionAuthorized,
  TransactionCreated,
  TransactionFailed,
  TransactionSucceeded
}
import aecor.util.Clock
import cats.Applicative
import cats.implicits._

import scala.collection.immutable._

class EventsourcedTransactionAggregate[F[_]](clock: Clock[F])(implicit F: Applicative[F])
    extends TransactionAggregate[Handler[F, Option[Transaction], TransactionEvent, ?]] {
  import F._

  override def createTransaction(
    transactionId: TransactionId,
    fromAccountId: From[AccountId],
    toAccountId: To[AccountId],
    amount: Amount
  ): Handler[F, Option[Transaction], TransactionEvent, Unit] =
    Handler {
      case None =>
        clock.instant.map { now =>
          Seq(
            TransactionEvent
              .TransactionCreated(transactionId, fromAccountId, toAccountId, amount, now)
          ) -> (())
        }

      case Some(_) => pure(Seq.empty -> (()))
    }

  override def authorizeTransaction(
    transactionId: TransactionId
  ): Handler[F, Option[Transaction], TransactionEvent, Either[String, Unit]] =
    Handler {
      case Some(transaction) =>
        clock.instant.map { now =>
          if (transaction.status == Requested) {
            Seq(TransactionAuthorized(transactionId, now)) -> ().asRight
          } else if (transaction.status == Authorized) {
            Seq() -> ().asRight
          } else {
            Seq() -> "Illegal transition".asLeft
          }
        }
      case None =>
        pure(Seq() -> "Transaction not found".asLeft)
    }

  override def failTransaction(
    transactionId: TransactionId,
    reason: String
  ): Handler[F, Option[Transaction], TransactionEvent, Either[String, Unit]] =
    Handler {
      case Some(transaction) =>
        clock.instant.map { now =>
          if (transaction.status == Failed) {
            Seq.empty -> ().asRight
          } else {
            Seq(TransactionFailed(transactionId, reason, now)) -> ().asRight
          }
        }
      case None =>
        pure(Seq.empty -> "Transaction not found".asLeft)
    }

  override def succeedTransaction(
    transactionId: TransactionId
  ): Handler[F, Option[Transaction], TransactionEvent, Either[String, Unit]] =
    Handler {
      case Some(transaction) =>
        clock.instant.map { now =>
          if (transaction.status == Succeeded) {
            Seq.empty -> ().asRight
          } else if (transaction.status == Authorized) {
            Seq(TransactionSucceeded(transactionId, now)) -> ().asRight
          } else {
            Seq.empty -> "Illegal transition".asLeft
          }
        }
      case None =>
        pure(Seq.empty -> "Transaction not found".asLeft)
    }

  override def getTransactionInfo(
    transactionId: TransactionId
  ): Handler[F, Option[Transaction], TransactionEvent, Option[TransactionInfo]] =
    Handler.readOnly(_.map {
      case Transaction(status, from, to, amount) =>
        TransactionInfo(transactionId, from, to, amount, Some(status).collect {
          case Succeeded => true
          case Failed    => false
        })
    })
}

object EventsourcedTransactionAggregate {
  def behavior[F[_]: Applicative](
    clock: Clock[F]
  ): EventsourcedBehavior[F, TransactionAggregateOp, Option[Transaction], TransactionEvent] =
    EventsourcedBehavior(
      TransactionAggregate.toFunctionK(new EventsourcedTransactionAggregate[F](clock)),
      Transaction.folder
    )
  sealed abstract class TransactionStatus
  object TransactionStatus {
    case object Requested extends TransactionStatus
    case object Authorized extends TransactionStatus
    case object Failed extends TransactionStatus
    case object Succeeded extends TransactionStatus
  }
  final case class Transaction(status: TransactionStatus,
                               from: From[AccountId],
                               to: To[AccountId],
                               amount: Amount) {
    def applyEvent(event: TransactionEvent): Folded[Transaction] = event match {
      case TransactionCreated(_, _, _, _, _) => impossible
      case TransactionAuthorized(_, _)       => copy(status = TransactionStatus.Authorized).next
      case TransactionFailed(_, _, _)        => copy(status = TransactionStatus.Failed).next
      case TransactionSucceeded(_, _)        => copy(status = TransactionStatus.Succeeded).next
    }
  }
  object Transaction {
    def fromEvent(event: TransactionEvent): Folded[Transaction] = event match {
      case TransactionEvent.TransactionCreated(transactionId, fromAccount, toAccount, amount, _) =>
        Transaction(TransactionStatus.Requested, fromAccount, toAccount, amount).next
      case _ => impossible
    }
    def folder: Folder[Folded, TransactionEvent, Option[Transaction]] =
      Folder.optionInstance(fromEvent)(x => x.applyEvent)
  }

}
