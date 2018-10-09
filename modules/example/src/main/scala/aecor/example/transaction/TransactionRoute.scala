package aecor.example.transaction

import java.util.UUID

import aecor.example.account
import aecor.example.account.AccountId
import aecor.example.common.Amount
import cats.effect.{Effect, Sync}
import cats.implicits._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityDecoder
import org.http4s.dsl.Http4sDsl
import io.circe.generic.auto._


trait TransactionService[F[_]] {
  def authorizePayment(transactionId: TransactionId,
                       from: From[AccountId],
                       to: To[AccountId],
                       amount: Amount): F[TransactionRoute.ApiResult]
}

object TransactionRoute {

  sealed trait ApiResult
  object ApiResult {
    case object Authorized extends ApiResult
    case class Declined(reason: String) extends ApiResult
  }

  final case class CreateTransactionRequest(from: From[AccountId],
                                            to: To[AccountId],
                                            amount: Amount)

  object TransactionIdVar {
    def unapply(arg: String): Option[TransactionId] = TransactionId(arg).some
  }

  private final class Builder[F[_]: Sync](service: TransactionService[F]) extends Http4sDsl[F] with CirceEntityDecoder {
    def routes: HttpRoutes[F] = HttpRoutes.of[F] {
      case req @ PUT -> Root / "transactions" / TransactionIdVar(transactionId) =>
        for {
          body <- req.as[CreateTransactionRequest]
          CreateTransactionRequest(from, to, amount) = body
          resp <- service.authorizePayment(transactionId, from, to, amount).flatMap {
            case ApiResult.Authorized =>
              Ok("Authorized")
            case ApiResult.Declined(reason) =>
              BadRequest(s"Declined: $reason")
          }
        } yield resp
      case POST -> Root / "test" =>
        service
          .authorizePayment(
            TransactionId(UUID.randomUUID.toString),
            From(account.EventsourcedAlgebra.rootAccountId),
            To(AccountId("foo")),
            Amount(1)
          )
          .flatMap {
          case ApiResult.Authorized =>
            Ok("Authorized")
          case ApiResult.Declined(reason) =>
            BadRequest(s"Declined: $reason")
        }
    }
  }

  def apply[F[_]: Effect](api: TransactionService[F]): HttpRoutes[F] =
    new Builder(api).routes

}
