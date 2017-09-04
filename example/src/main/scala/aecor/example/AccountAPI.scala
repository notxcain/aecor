package aecor.example

import aecor.example.domain._
import akka.Done
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.implicits._
import cats.~>
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport

import scala.concurrent.{ ExecutionContext, Future }

class AccountAPI(account: AccountAggregateOp ~> Future) {

  import AccountAPI._

  def openAccount(dto: DTO.OpenAccount)(implicit ec: ExecutionContext): Future[String Either Done] =
    dto match {
      case DTO.OpenAccount(accountId) =>
        account(AccountAggregateOp.OpenAccount(AccountId(accountId)))
          .map(_.leftMap(_.toString))
    }

  def creditAccount(
    dto: DTO.CreditAccount
  )(implicit ec: ExecutionContext): Future[String Either Done] = dto match {
    case DTO.CreditAccount(accountId, transactionId, amount) =>
      account(
        AccountAggregateOp
          .CreditAccount(AccountId(accountId), TransactionId(transactionId), Amount(amount))
      ).map(_.leftMap(_.toString))
  }
}

object AccountAPI extends FailFastCirceSupport {
  import io.circe.generic.auto._

  sealed trait DTO

  object DTO {
    case class CreditAccount(accountId: String, transactionId: String, amount: Long) extends DTO
    case class OpenAccount(accountId: String) extends DTO
  }

  val route: AccountAPI => Route = { api =>
    path("accounts") {
      extractExecutionContext { implicit ec =>
        post {
          entity(as[DTO]) {
            case dto: DTO.CreditAccount =>
              complete {
                api.creditAccount(dto).map {
                  case Left(e)       => StatusCodes.BadRequest -> e.toString
                  case Right(result) => StatusCodes.OK -> ""
                }
              }
            case dto: DTO.OpenAccount =>
              complete {
                api.openAccount(dto).map {
                  case Left(e)       => StatusCodes.BadRequest -> e.toString
                  case Right(result) => StatusCodes.OK -> ""
                }
              }
          }
        }
      }
    }
  }

}
