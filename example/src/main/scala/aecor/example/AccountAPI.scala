package aecor.example

import aecor.api.Router
import aecor.core.aggregate.AggregateRegionRef
import aecor.example.domain._
import akka.Done
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import de.heikoseeberger.akkahttpcirce.CirceSupport._
import io.circe.generic.JsonCodec
import cats.implicits._
import scala.concurrent.{ExecutionContext, Future}

class AccountAPI(account: AggregateRegionRef[AccountAggregateOp]) {

  import AccountAPI._

  def openAccount(dto: DTO.OpenAccount)(
      implicit ec: ExecutionContext): Future[String Either Done] = dto match {
    case DTO.OpenAccount(accountId) =>
      account
        .ask(AccountAggregateOp.OpenAccount(AccountId(accountId)))
        .map(_.leftMap(_.toString))
  }

  def creditAccount(dto: DTO.CreditAccount)(
      implicit ec: ExecutionContext): Future[String Either Done] = dto match {
    case DTO.CreditAccount(accountId, transactionId, amount) =>
      account
        .ask(
          AccountAggregateOp.CreditAccount(AccountId(accountId),
                                           TransactionId(transactionId),
                                           Amount(amount)))
        .map(_.leftMap(_.toString))
  }
}

object AccountAPI {

  @JsonCodec sealed trait DTO

  object DTO {
    case class CreditAccount(accountId: String,
                             transactionId: String,
                             amount: Long)
        extends DTO
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
                  case Left(e) => StatusCodes.BadRequest -> e.toString
                  case Right(result) => StatusCodes.OK -> ""
                }
              }
            case dto: DTO.OpenAccount =>
              complete {
                api.openAccount(dto).map {
                  case Left(e) => StatusCodes.BadRequest -> e.toString
                  case Right(result) => StatusCodes.OK -> ""
                }
              }
          }
        }
      }
    }
  }

}
