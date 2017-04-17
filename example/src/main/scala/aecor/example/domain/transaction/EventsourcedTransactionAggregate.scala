package aecor.example.domain.transaction

import aecor.aggregate.Folder
import aecor.data.{ Folded, Handler }
import aecor.example.domain.Amount
import aecor.example.domain.account.AccountId
import aecor.example.domain.transaction.EventsourcedTransactionAggregate.Transaction
import aecor.example.domain.transaction.EventsourcedTransactionAggregate.TransactionStatus.{
  Authorized,
  Failed,
  Requested,
  Succeeded
}
import aecor.example.domain.transaction.TransactionAggregate.TransactionInfo
import aecor.example.domain.transaction.TransactionEvent.{
  TransactionAuthorized,
  TransactionCreated,
  TransactionFailed,
  TransactionSucceeded
}
import cats.Applicative
import cats.implicits._
import aecor.data.Folded.syntax._

import scala.collection.immutable._

class EventsourcedTransactionAggregate[F[_]: Applicative]
    extends TransactionAggregate[Handler[F, Option[Transaction], Seq[TransactionEvent], ?]] {
  private def handle = Handler.lift[F, Option[Transaction]]

  override def createTransaction(
    transactionId: TransactionId,
    fromAccountId: From[AccountId],
    toAccountId: To[AccountId],
    amount: Amount
  ): Handler[F, Option[Transaction], Seq[TransactionEvent], Unit] =
    handle {
      case None =>
        (
          Seq(
            TransactionEvent.TransactionCreated(transactionId, fromAccountId, toAccountId, amount)
          ),
          ()
        )
      case Some(_) => (Seq.empty, ())
    }

  override def authorizeTransaction(
    transactionId: TransactionId
  ): Handler[F, Option[Transaction], Seq[TransactionEvent], Either[String, Unit]] =
    handle {
      case Some(transaction) =>
        if (transaction.status == Requested) {
          Seq(TransactionAuthorized(transactionId)) -> ().asRight
        } else if (transaction.status == Authorized) {
          Seq() -> ().asRight
        } else {
          Seq() -> "Illegal transition".asLeft
        }
      case None =>
        Seq() -> "Transaction not found".asLeft
    }

  override def failTransaction(
    transactionId: TransactionId,
    reason: String
  ): Handler[F, Option[Transaction], Seq[TransactionEvent], Either[String, Unit]] =
    handle {
      case Some(transaction) =>
        if (transaction.status == Failed) {
          Seq.empty -> ().asRight
        } else {
          Seq(TransactionFailed(transactionId, reason)) -> ().asRight
        }
      case None =>
        Seq.empty -> "Transaction not found".asLeft
    }

  override def succeedTransaction(
    transactionId: TransactionId
  ): Handler[F, Option[Transaction], Seq[TransactionEvent], Either[String, Unit]] =
    handle {
      case Some(transaction) =>
        if (transaction.status == Succeeded) {
          Seq.empty -> ().asRight
        } else if (transaction.status == Authorized) {
          Seq(TransactionSucceeded(transactionId)) -> ().asRight
        } else {
          Seq.empty -> "Illegal transition".asLeft
        }
      case None =>
        Seq.empty -> "Transaction not found".asLeft
    }

  override def getTransactionInfo(
    transactionId: TransactionId
  ): Handler[F, Option[Transaction], Seq[TransactionEvent], Option[TransactionInfo]] =
    handle(
      s =>
        Seq.empty -> s.map {
          case Transaction(status, from, to, amount) =>
            TransactionInfo(transactionId, from, to, amount, Some(status).collect {
              case Succeeded => true
              case Failed => false
            })
      }
    )
}

object EventsourcedTransactionAggregate {
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
      case TransactionCreated(_, _, _, _) => impossible
      case TransactionAuthorized(_) => copy(status = TransactionStatus.Authorized).next
      case TransactionFailed(_, _) => copy(status = TransactionStatus.Failed).next
      case TransactionSucceeded(_) => copy(status = TransactionStatus.Succeeded).next
    }
  }
  object Transaction {
    def fromEvent(event: TransactionEvent): Folded[Transaction] = event match {
      case TransactionEvent.TransactionCreated(transactionId, fromAccount, toAccount, amount) =>
        Transaction(TransactionStatus.Requested, fromAccount, toAccount, amount).next
      case _ => impossible
    }
    implicit def folder: Folder[Folded, TransactionEvent, Option[Transaction]] =
      Folder.optionInstance(fromEvent)(x => x.applyEvent)
  }

}
