package aecor.example.domain.transaction

import aecor.data.Folded.syntax._
import aecor.data._
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

import cats.implicits._

import scala.collection.immutable._

class EventsourcedTransactionAggregate
    extends TransactionAggregate[Action[Option[Transaction], TransactionEvent, ?]] {

  override def create(fromAccountId: From[AccountId],
                      toAccountId: To[AccountId],
                      amount: Amount): Action[Option[Transaction], TransactionEvent, Unit] =
    Action {
      case None =>
        List(
          TransactionEvent
            .TransactionCreated(fromAccountId, toAccountId, amount)
        ) -> (())

      case Some(_) => List.empty -> (())
    }

  override def authorize: Action[Option[Transaction], TransactionEvent, Either[String, Unit]] =
    Action {
      case Some(transaction) =>
        if (transaction.status == Requested) {
          List(TransactionAuthorized) -> ().asRight
        } else if (transaction.status == Authorized) {
          List.empty -> ().asRight
        } else {
          List.empty -> "Illegal transition".asLeft
        }
      case None =>
        List.empty -> "Transaction not found".asLeft
    }

  override def fail(
    reason: String
  ): Action[Option[Transaction], TransactionEvent, Either[String, Unit]] =
    Action {
      case Some(transaction) =>
        if (transaction.status == Failed) {
          List.empty -> ().asRight
        } else {
          List(TransactionFailed(reason)) -> ().asRight
        }
      case None =>
        List.empty -> "Transaction not found".asLeft
    }

  override def succeed: Action[Option[Transaction], TransactionEvent, Either[String, Unit]] =
    Action {
      case Some(transaction) =>
        if (transaction.status == Succeeded) {
          List.empty -> ().asRight
        } else if (transaction.status == Authorized) {
          List(TransactionSucceeded) -> ().asRight
        } else {
          List.empty -> "Illegal transition".asLeft
        }
      case None =>
        List.empty -> "Transaction not found".asLeft
    }

  override def getInfo: Action[Option[Transaction], TransactionEvent, Option[TransactionInfo]] =
    Action.read(_.map {
      case Transaction(status, from, to, amount) =>
        TransactionInfo(from, to, amount, Some(status).collect {
          case Succeeded => true
          case Failed    => false
        })
    })
}

object EventsourcedTransactionAggregate {

  val actions: TransactionAggregate[Action[Option[Transaction], TransactionEvent, ?]] =
    new EventsourcedTransactionAggregate()

  def behavior: EventsourcedBehavior[TransactionAggregate, Option[Transaction], TransactionEvent] =
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
