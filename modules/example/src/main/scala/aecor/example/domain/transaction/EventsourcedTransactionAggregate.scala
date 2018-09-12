package aecor.example.domain.transaction

import aecor.data.Folded.syntax._
import aecor.data.Tagging
import aecor.data.next.{ActionT, EventsourcedBehavior, MonadAction}
import aecor.example.domain.Amount
import aecor.example.domain.account.AccountId
import aecor.example.domain.transaction.EventsourcedTransactionAggregate.Transaction
import aecor.example.domain.transaction.EventsourcedTransactionAggregate.TransactionStatus.{Authorized, Failed, Requested, Succeeded}
import aecor.example.domain.transaction.TransactionAggregate._
import aecor.example.domain.transaction.TransactionEvent.{TransactionAuthorized, TransactionCreated, TransactionFailed, TransactionSucceeded}
import cats.Monad
import cats.implicits._

import scala.collection.immutable._

class EventsourcedTransactionAggregate[F[_]](
  implicit F: MonadAction[F, Option[Transaction], TransactionEvent, String]
) extends TransactionAggregate[F] {
  import F._
  override def create(fromAccountId: From[AccountId],
                      toAccountId: To[AccountId],
                      amount: Amount): F[Unit] =
    read.flatMap {
      case None =>
        append(TransactionEvent.TransactionCreated(fromAccountId, toAccountId, amount))
      case Some(_) =>
        ().pure[F]
    }

  override def authorize: F[Unit] =
    read.flatMap {
      case Some(transaction) =>
        if (transaction.status == Requested) {
          append(TransactionAuthorized)
        } else if (transaction.status == Authorized) {
          ().pure[F]
        } else {
          reject("Illegal transition")
        }
      case None =>
        reject("Transaction not found")
    }

  override def fail(reason: String): F[Unit] =
    read.flatMap {
      case Some(transaction) =>
        if (transaction.status == Failed) {
          ().pure[F]
        } else {
          append(TransactionFailed(reason))
        }
      case None =>
        reject("Transaction not found")
    }

  override def succeed: F[Unit] =
    read.flatMap {
      case Some(transaction) =>
        if (transaction.status == Succeeded) {
          ().pure[F]
        } else if (transaction.status == Authorized) {
          append(TransactionSucceeded)
        } else {
          reject("Illegal transition")
        }
      case None =>
        reject("Transaction not found")
    }

  override def getInfo: F[TransactionInfo] =
    read.flatMap {
      case Some(Transaction(status, from, to, amount)) =>
        TransactionInfo(from, to, amount, Some(status).collect {
          case Succeeded => true
          case Failed    => false
        }).pure[F]
      case None =>
        reject("Transaction not found")
    }
}

object EventsourcedTransactionAggregate {

  def actions[F[_]: Monad]: TransactionAggregate[ActionT[F, Option[Transaction], TransactionEvent, String, ?]] =
    new EventsourcedTransactionAggregate()

  def behavior[F[_]: Monad]: EventsourcedBehavior[TransactionAggregate, F, Option[Transaction], TransactionEvent, String] =
    EventsourcedBehavior
      .optional(actions, Transaction.fromEvent, _.applyEvent(_))

  def tagging: Tagging.Partitioned[TransactionId] =
    Tagging.partitioned(20)(EventTag("Transaction"))

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
      case TransactionCreated(_, _, _) => impossible
      case TransactionAuthorized       => copy(status = TransactionStatus.Authorized).next
      case TransactionFailed(_)        => copy(status = TransactionStatus.Failed).next
      case TransactionSucceeded        => copy(status = TransactionStatus.Succeeded).next
    }
  }
  object Transaction {
    def fromEvent(event: TransactionEvent): Folded[Transaction] = event match {
      case TransactionEvent.TransactionCreated(fromAccount, toAccount, amount) =>
        Transaction(TransactionStatus.Requested, fromAccount, toAccount, amount).next
      case _ => impossible
    }
  }

}
