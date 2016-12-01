package aecor.example

import aecor.api.Router
import aecor.core.aggregate.AggregateRegionRef
import aecor.example.AuthorizePaymentAPI._
import aecor.example.domain.CardAuthorization.{
  CardAuthorizationAccepted,
  CardAuthorizationDeclined,
  CreateCardAuthorization
}
import aecor.example.domain._
import akka.event.LoggingAdapter
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._

import de.heikoseeberger.akkahttpcirce.CirceSupport._
import io.circe.generic.JsonCodec

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class AuthorizePaymentAPI(
    authorization: AggregateRegionRef[CardAuthorization.Command],
    eventStream: EventStream[CardAuthorization.Event],
    log: LoggingAdapter) {

  import DTO._

  def authorizePayment(dto: AuthorizePayment)(implicit ec: ExecutionContext)
    : Future[Either[CardAuthorization.CreateCardAuthorizationRejection,
                    AuthorizePaymentAPI.ApiResult]] = dto match {
    case AuthorizePayment(cardAuthorizationId,
                          accountId,
                          amount,
                          acquireId,
                          terminalId) =>
      val command = CreateCardAuthorization(
        CardAuthorizationId(cardAuthorizationId),
        AccountId(accountId),
        Amount(amount),
        AcquireId(acquireId),
        TerminalId(terminalId)
      )
      log.debug("Sending command [{}]", command)
      val start = System.nanoTime()
      eventStream
        .registerObserver(30.seconds) {
          case e: CardAuthorizationDeclined
              if e.cardAuthorizationId.value == cardAuthorizationId =>
            AuthorizePaymentAPI.ApiResult.Declined(e.reason.toString)
          case e: CardAuthorizationAccepted
              if e.cardAuthorizationId.value == cardAuthorizationId =>
            AuthorizePaymentAPI.ApiResult.Authorized
        }
        .flatMap { observer =>
          authorization
            .ask(command)
            .flatMap {
              case Left(rejection) => Future.successful(Left(rejection))
              case _ => observer.result.map(Right(_))
            }
            .map { x =>
              log.debug("Command [{}] processed with result [{}] in [{}]",
                        command,
                        x,
                        (System.nanoTime() - start) / 1000000)
              x
            }
        }

  }
}

object AuthorizePaymentAPI {

  sealed trait ApiResult
  object ApiResult {
    case object Authorized extends ApiResult
    case class Declined(reason: String) extends ApiResult
  }

  @JsonCodec sealed trait DTO

  object DTO {

    case class AuthorizePayment(cardAuthorizationId: String,
                                accountId: String,
                                amount: Long,
                                acquireId: Long,
                                terminalId: Long)
        extends DTO

  }

  implicit val router: Router[AuthorizePaymentAPI] = Router.instance { api =>
    path("authorization") {
      extractExecutionContext { implicit ec =>
        post {
          entity(as[DTO]) {
            case dto: DTO.AuthorizePayment =>
              complete {
                api.authorizePayment(dto).map {
                  case Left(e) => StatusCodes.BadRequest -> e.toString
                  case Right(result) =>
                    result match {
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
    }
  }
}
