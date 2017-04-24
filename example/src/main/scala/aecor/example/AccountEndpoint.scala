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
import AnyValCirceEncoding._
import aecor.example.AccountEndpoint.AccountApiRequest.OpenAccountRequest

class AccountEndpoint(account: AccountAggregate[Task]) {

  def openAccount(accountId: AccountId): Task[String Either Unit] =
    account
      .openAccount(accountId)
      .map(_.leftMap(_.toString))
}

object AccountEndpoint {

  sealed abstract class AccountApiRequest
  object AccountApiRequest {
    @JsonCodec final case class OpenAccountRequest(accountId: AccountId) extends AccountApiRequest
  }

  def route(api: AccountEndpoint): Route =
    pathPrefix("account") {
      post {
        (path("open") & entity(as[AccountApiRequest.OpenAccountRequest])) {
          case OpenAccountRequest(accountId) =>
            complete {
              api.openAccount(accountId).map {
                case Left(e) => StatusCodes.BadRequest -> e.toString
                case Right(result) => StatusCodes.OK -> ""
              }
            }
        }
      }
    }
}
