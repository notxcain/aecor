package aecor.example

import java.util.UUID

import aecor.example.MonixSupport._
import aecor.example.TransactionEndpoint.TransactionEndpointRequest.CreateTransactionRequest
import aecor.example.TransactionEndpoint._
import aecor.example.domain._
import aecor.example.domain.account.{ AccountId, EventsourcedAccountAggregate }
import aecor.example.domain.transaction._
import akka.event.LoggingAdapter
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import CirceSupport._
import monix.eval.Task
import io.circe.Decoder
import io.circe.generic.decoding.DerivedDecoder
import shapeless.Lazy

import scala.concurrent.duration._

class TransactionEndpoint(transactions: TransactionAggregate[Task], log: LoggingAdapter) {

  import TransactionEndpointRequest._

  def authorizePayment(request: CreateTransactionRequest): Task[TransactionEndpoint.ApiResult] =
    request match {
      case CreateTransactionRequest(transactionId, fromAccountId, toAccountId, amount) =>
        log.debug("Processing request [{}]", request)
        val start = System.nanoTime()
        transactions
          .createTransaction(transactionId, fromAccountId, toAccountId, amount)
          .flatMap { _ =>
            transactions
              .getTransactionInfo(transactionId)
              .flatMap {
                case Some(t) => Task.pure(t)
                case None => Task.raiseError(new IllegalStateException("Something went bad"))
              }
              .delayExecution(5.millis)
              .restartUntil(_.succeeded.isDefined)
              .timeout(30.seconds)
              .map(_.succeeded.get)
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

  sealed trait TransactionEndpointRequest

  object TransactionEndpointRequest {

    case class CreateTransactionRequest(transactionId: TransactionId,
                                        from: From[AccountId],
                                        to: To[AccountId],
                                        amount: Amount)
        extends TransactionEndpointRequest

  }

  implicit def requestDecoder[A <: TransactionEndpointRequest](
    implicit A: Lazy[DerivedDecoder[A]]
  ): Decoder[A] = A.value

  def route(api: TransactionEndpoint): Route =
    pathPrefix("transaction") {
      post {
        (path("create") & entity(as[CreateTransactionRequest])) { request =>
          complete {
            api.authorizePayment(request).map[ToResponseMarshallable] {
              case ApiResult.Authorized =>
                StatusCodes.OK -> "Authorized"
              case ApiResult.Declined(reason) =>
                StatusCodes.BadRequest -> s"Declined: $reason"
            }
          }
        } ~ path("test") {
          complete {
            api
              .authorizePayment(
                CreateTransactionRequest(
                  TransactionId(UUID.randomUUID.toString),
//                  From(AccountId("foo" + scala.util.Random.nextInt(20))),
                  From(EventsourcedAccountAggregate.rootAccountId),
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
    }

}
