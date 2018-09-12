package aecor.example

import java.util.UUID

import aecor.example.CirceSupport._
import aecor.example.EffectSupport._
import aecor.example.TransactionEndpoint.TransactionEndpointRequest.CreateTransactionRequest
import aecor.example.TransactionEndpoint._
import aecor.example.domain._
import aecor.example.domain.account.{AccountId, EventsourcedAccount}
import aecor.example.domain.transaction._
import akka.event.LoggingAdapter
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.effect.{Concurrent, Effect, Timer}
import cats.implicits._
import io.circe.Decoder
import io.circe.generic.decoding.DerivedDecoder
import shapeless.Lazy

import scala.concurrent.duration._

class TransactionEndpoint[F[_]](transactions: TransactionId => TransactionAggregate[F],
                          log: LoggingAdapter)(implicit F: Concurrent[F], timer: Timer[F]) {

  import TransactionEndpointRequest._

  def authorizePayment(transactionId: TransactionId,
                       request: CreateTransactionRequest): F[TransactionEndpoint.ApiResult] =
    request match {
      case CreateTransactionRequest(fromAccountId, toAccountId, amount) =>
        log.debug("Processing request [{}]", request)
        val start = System.nanoTime()
        transactions(transactionId)
          .create(fromAccountId, toAccountId, amount)
          .flatMap { _ =>
            val getTransaction = transactions(transactionId).getInfo
              .flatMap {
                case Some(t) => t.pure[F]
                case None    => F.raiseError[TransactionAggregate.TransactionInfo](new IllegalStateException("Something went bad"))
              }
            def loop: F[Boolean] = getTransaction.flatMap {
              case TransactionAggregate.TransactionInfo(_, _, _, Some(value)) => value.pure[F]
              case _ => timer.sleep(5.millis) >> loop
            }
            Concurrent.timeout(loop, 30.seconds)
          }
          .map { succeeded =>
            if (succeeded) {
              ApiResult.Authorized
            } else {
              ApiResult.Declined("You suck")
            }
          }
          .map { x =>
            log.debug(
              "Request [{}] processed with result [{}] in [{}ms]",
              request,
              x,
              (System.nanoTime() - start) / 1000000
            )
            x
          }

    }
}

object TransactionEndpoint {

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

  def route[F[_]: Effect](api: TransactionEndpoint[F]): Route =
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
              From(EventsourcedAccount.rootAccountId),
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
