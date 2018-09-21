package aecor.example.account

import aecor.example.EffectSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.effect.Effect
import cats.implicits._
import io.circe.generic.auto._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

trait AccountService[F[_]] {
  def openAccount(accountId: AccountId, checkBalance: Boolean): F[Either[String, Unit]]
}

object AccountRoute {

  final case class OpenAccountRequest(accountId: AccountId, checkBalance: Boolean)

  implicit val openAccountRequestDecoder =
    io.circe.generic.semiauto.deriveDecoder[OpenAccountRequest]

  def apply[F[_]: Effect](service: AccountService[F]): Route =
    pathPrefix("accounts") {
      (post & entity(as[OpenAccountRequest])) {
        case OpenAccountRequest(accountId, checkBalance) =>
          complete {
            service.openAccount(accountId, checkBalance).map {
              case Left(e)       => StatusCodes.BadRequest -> e.toString
              case Right(_) => StatusCodes.OK -> ""
            }
          }
      }
    }

}
