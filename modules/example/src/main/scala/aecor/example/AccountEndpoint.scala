package aecor.example

import aecor.example.MonixSupport._
import aecor.example.domain.account.{ Account, AccountId }
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import monix.eval.Task
import AnyValCirceEncoding._
import cats.implicits._

class AccountEndpoint(accounts: AccountId => Account[Task]) {
  def openAccount(accountId: AccountId, checkBalance: Boolean): Task[String Either Unit] =
    accounts(accountId).open(checkBalance).map(_.leftMap(_.toString))
}

object AccountEndpoint {

  final case class OpenAccountRequest(accountId: AccountId, checkBalance: Boolean)

  implicit val openAccountRequestDecoder =
    io.circe.generic.semiauto.deriveDecoder[OpenAccountRequest]

  def route(api: AccountEndpoint): Route =
    pathPrefix("accounts") {
      (post & entity(as[OpenAccountRequest])) {
        case OpenAccountRequest(accountId, checkBalance) =>
          complete {
            api.openAccount(accountId, checkBalance).map {
              case Left(e)       => StatusCodes.BadRequest -> e.toString
              case Right(result) => StatusCodes.OK -> ""
            }
          }
      }
    }

}
