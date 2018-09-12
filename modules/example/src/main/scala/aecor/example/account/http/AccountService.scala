package aecor.example.account.http

import aecor.example.EffectSupport._
import aecor.example.domain.account.AccountId
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.effect.Effect
import cats.implicits._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

trait Service[F[_]] {
  def openAccount(accountId: AccountId, checkBalance: Boolean): F[Either[String, Unit]]
}


object AccountRoute {

  final case class OpenAccountRequest(accountId: AccountId, checkBalance: Boolean)

  implicit val openAccountRequestDecoder =
    io.circe.generic.semiauto.deriveDecoder[OpenAccountRequest]

  def apply[F[_]: Effect](implicit service: Service[F]): Route =
    pathPrefix("accounts") {
      (post & entity(as[OpenAccountRequest])) {
        case OpenAccountRequest(accountId, checkBalance) =>
          complete {
            service.openAccount(accountId, checkBalance).map {
              case Left(e)       => StatusCodes.BadRequest -> e.toString
              case Right(result) => StatusCodes.OK -> ""
            }
          }
      }
    }

}
