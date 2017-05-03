package aecor.example.domain.transaction

import aecor.data.Folded
import aecor.data.Folded.Next
import aecor.example.domain.Amount
import aecor.example.domain.account.AccountId
import aecor.example.domain.transaction.EventsourcedTransactionAggregate.TransactionStatus
import aecor.runtime.akkapersistence.AggregateProjection

final case class TransactionProjection(transactionId: TransactionId,
                                       status: TransactionStatus,
                                       from: From[AccountId],
                                       to: To[AccountId],
                                       amount: Amount,
                                       version: Long)

object TransactionProjection {
  implicit def instance: AggregateProjection[TransactionEvent, TransactionProjection] =
    AggregateProjection.instance {
      case Some(s) => {
        case x => Folded.impossible
      }
      case None => {
        case TransactionEvent.TransactionCreated(transactionId, from, to, amount) =>
          Folded.next(
            TransactionProjection(transactionId, TransactionStatus.Requested, from, to, amount, 1)
          )
        case _ => Folded.impossible
      }
    }
}
