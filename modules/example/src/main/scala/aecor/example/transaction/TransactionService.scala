package aecor.example.transaction

import java.util.UUID

import aecor.example.CirceSupport._
import aecor.example.EffectSupport._
import aecor.example.account
import aecor.example.account.AccountId
import aecor.example.common.Amount
import aecor.example.transaction.TransactionRoute.TransactionEndpointRequest.CreateTransactionRequest
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.effect.Effect
import cats.implicits._
import io.circe.Decoder
import io.circe.generic.decoding.DerivedDecoder
import shapeless.Lazy


trait TransactionService[F[_]] {
  def authorizePayment(transactionId: TransactionId,
                       request: CreateTransactionRequest): F[TransactionRoute.ApiResult]
}

object TransactionRoute {

  sealed trait ApiResult
  object ApiResult {
    case object Authorized extends ApiResult
    case class Declined(reason: String) extends ApiResult
  }

  sealed abstract class TransactionEndpointRequest

  object TransactionEndpointRequest {

    final case class CreateTransactionRequest(from: From[AccountId],
                                              to: To[AccountId],
                                              amount: Amount)
        extends TransactionEndpointRequest

  }

  implicit def requestDecoder[A <: TransactionEndpointRequest](
    implicit A: Lazy[DerivedDecoder[A]]
  ): Decoder[A] = A.value

  def apply[F[_]: Effect](api: TransactionService[F]): Route =
    (put & pathPrefix("transactions" / Segment.map(TransactionId(_)))) { transactionId =>
      entity(as[CreateTransactionRequest]) { request =>
        complete {
          api.authorizePayment(transactionId, request).map[ToResponseMarshallable] {
            case ApiResult.Authorized =>
              StatusCodes.OK -> "Authorized"
            case ApiResult.Declined(reason) =>
              StatusCodes.BadRequest -> s"Declined: $reason"
          }
        }
      }

    } ~ (post & path("test")) {
      complete {
        api
          .authorizePayment(
            TransactionId(UUID.randomUUID.toString),
            CreateTransactionRequest(
              From(account.EventsourcedAlgebra.rootAccountId),
              To(AccountId("foo" + scala.util.Random.nextInt(20))),
              Amount(1)
            )
          )
          .map[ToResponseMarshallable] {
            case ApiResult.Authorized =>
              StatusCodes.OK -> "Authorized"
            case ApiResult.Declined(reason) =>
              StatusCodes.BadRequest -> s"Declined: $reason"
          }
      }
    }

}
