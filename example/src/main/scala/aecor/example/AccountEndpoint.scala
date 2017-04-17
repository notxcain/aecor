package aecor.example

import aecor.example.MonixSupport._
import aecor.example.domain.account.{ AccountAggregate, AccountId }
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.implicits._
import de.heikoseeberger.akkahttpcirce.CirceSupport._
import io.circe.generic.JsonCodec
import monix.eval.Task

class AccountEndpoint(account: AccountAggregate[Task]) {

  import AccountEndpoint._

  def openAccount(dto: AccountApiRequest.OpenAccountRequest): Task[String Either Unit] =
    dto match {
      case AccountApiRequest.OpenAccountRequest(accountId) =>
        account
          .openAccount(AccountId(accountId))
          .map(_.leftMap(_.toString))

    }
}

object AccountEndpoint {

  sealed abstract class AccountApiRequest
  object AccountApiRequest {
    @JsonCodec final case class OpenAccountRequest(accountId: String) extends AccountApiRequest
  }

  def route(api: AccountEndpoint): Route =
    pathPrefix("account") {
      post {
        (path("open") & entity(as[AccountApiRequest.OpenAccountRequest])) { request =>
          complete {
            api.openAccount(request).map {
              case Left(e) => StatusCodes.BadRequest -> e.toString
              case Right(result) => StatusCodes.OK -> ""
            }
          }
        }
      }
    }
}
