package aecor.example

import aecor.api.Router
import aecor.core.aggregate.{AggregateRegionRef, AggregateResponse}
import aecor.example.domain.{Account, AccountId, Amount, TransactionId}
import akka.Done
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import cats.data.Xor
import io.circe.generic.JsonCodec
import de.heikoseeberger.akkahttpcirce.CirceSupport._

import scala.concurrent.{ExecutionContext, Future}

class AccountAPI(account: AggregateRegionRef[Account.Command]) {

  import AccountAPI._

  def openAccount(dto: DTO.OpenAccount)(implicit ec: ExecutionContext): Future[String Xor Done] = dto match {
    case DTO.OpenAccount(accountId) =>
      account
      .ask(Account.OpenAccount(AccountId(accountId)))
      .flatMap {
        case AggregateResponse.Accepted =>
          Future.successful(Xor.Right(Done: Done))
        case AggregateResponse.Rejected(rejection) =>
          Future.successful(Xor.Left(rejection.toString))
      }
  }

  def creditAccount(dto: DTO.CreditAccount)(implicit ec: ExecutionContext): Future[String Xor Done] = dto match {
    case DTO.CreditAccount(accountId, transactionId, amount) =>
      account
      .ask(Account.CreditAccount(AccountId(accountId), TransactionId(transactionId), Amount(amount)))
      .flatMap {
        case AggregateResponse.Accepted =>
          Future.successful(Xor.Right(Done: Done))
        case AggregateResponse.Rejected(rejection) =>
          Future.successful(Xor.Left(rejection.toString))
      }
  }
}

object AccountAPI {

  @JsonCodec sealed trait DTO

  object DTO {
    case class CreditAccount(accountId: String, transactionId: String, amount: Long) extends DTO
    case class OpenAccount(accountId: String) extends DTO
  }

  implicit val router: Router[AccountAPI] = Router.instance { api =>
    path("accounts") {
      extractExecutionContext { implicit ec =>
        post {
          entity(as[DTO]) {
            case dto: DTO.CreditAccount =>
              complete {
                api.creditAccount(dto).map {
                  case Xor.Left(e) => StatusCodes.BadRequest -> e.toString
                  case Xor.Right(result) => StatusCodes.OK -> ""
                }
              }
            case dto: DTO.OpenAccount =>
              complete {
                api.openAccount(dto).map {
                  case Xor.Left(e) => StatusCodes.BadRequest -> e.toString
                  case Xor.Right(result) => StatusCodes.OK -> ""
                }
              }
          }
        }
      }
    }
  }

}