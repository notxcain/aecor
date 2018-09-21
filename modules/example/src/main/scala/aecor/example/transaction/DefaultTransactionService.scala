package aecor.example.transaction
import aecor.example.transaction.TransactionRoute.ApiResult
import aecor.example.transaction.TransactionRoute.TransactionEndpointRequest.CreateTransactionRequest
import aecor.example.transaction.transaction.Transactions
import cats.effect.{Concurrent, Timer}
import cats.implicits._
import scala.concurrent.duration._

class DefaultTransactionService[F[_]](transactions: Transactions[F])(implicit F: Concurrent[F], timer: Timer[F]) extends TransactionService[F] {

  def authorizePayment(transactionId: TransactionId,
                       request: CreateTransactionRequest): F[TransactionRoute.ApiResult] =
    request match {
      case CreateTransactionRequest(fromAccountId, toAccountId, amount) =>
        transactions(transactionId)
          .create(fromAccountId, toAccountId, amount)
          .flatMap { _ =>
            val getTransaction = transactions(transactionId).getInfo.value
              .flatMap {
                case Right(t) => t.pure[F]
                case _    => F.raiseError[Algebra.TransactionInfo](new IllegalStateException("Something went bad"))
              }
            def loop: F[Boolean] = getTransaction.flatMap {
              case Algebra.TransactionInfo(_, _, _, Some(value)) => value.pure[F]
              case _ => timer.sleep(100.millis) >> loop
            }
            Concurrent.timeout(loop, 10.seconds)
          }
          .map { succeeded =>
            if (succeeded) {
              ApiResult.Authorized
            } else {
              ApiResult.Declined("You suck")
            }
          }


    }
}

object DefaultTransactionService {
  def apply[F[_]](transactions: Transactions[F])(implicit F: Concurrent[F], timer: Timer[F]): TransactionService[F]=
    new DefaultTransactionService[F](transactions)
}
