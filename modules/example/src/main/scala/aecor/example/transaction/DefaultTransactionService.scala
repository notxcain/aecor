package aecor.example.transaction

import aecor.example.account.AccountId
import aecor.example.common.Amount
import aecor.example.transaction.transaction.Transactions
import aecor.example.transaction.TransactionRoute.ApiResult
import cats.effect.Temporal
import cats.syntax.all._

import scala.concurrent.duration._

final class DefaultTransactionService[F[_]](transactions: Transactions[F])(implicit
    F: Temporal[F]
) extends TransactionService[F] {

  def authorizePayment(
      transactionId: TransactionId,
      from: From[AccountId],
      to: To[AccountId],
      amount: Amount
  ): F[TransactionRoute.ApiResult] =
    transactions(transactionId)
      .create(from, to, amount)
      .flatMap { _ =>
        val getTransaction = transactions(transactionId).getInfo
          .flatMap {
            case Right(t) => t.pure[F]
            case _ =>
              F.raiseError[Algebra.TransactionInfo](new IllegalStateException("Something went bad"))
          }
        def loop: F[Boolean] = getTransaction.flatMap {
          case Algebra.TransactionInfo(_, _, _, Some(value)) => value.pure[F]
          case _                                             => F.sleep(10.millis) >> loop
        }
        F.timeout(loop, 10.seconds)
      }
      .map { succeeded =>
        if (succeeded) {
          ApiResult.Authorized
        } else {
          ApiResult.Declined("You suck")
        }
      }
}

object DefaultTransactionService {
  def apply[F[_]: Temporal](transactions: Transactions[F]): TransactionService[F] =
    new DefaultTransactionService[F](transactions)
}
