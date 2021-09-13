package aecor.example.account

import cats.effect._
import cats.syntax.all._
import io.circe.generic.auto._
import org.http4s._
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl

trait AccountService[F[_]] {
  def openAccount(accountId: AccountId, checkBalance: Boolean): F[Either[String, Unit]]
}

object AccountRoute {
  class Builder[F[_]: Concurrent](service: AccountService[F])
      extends Http4sDsl[F]
      with CirceEntityDecoder {
    def routes: HttpRoutes[F] =
      HttpRoutes.of[F] {
        case req @ POST -> Root / "accounts" =>
          for {
            openAccountRequest <- req.as[OpenAccountRequest]
            resp <- service
                     .openAccount(openAccountRequest.accountId, openAccountRequest.checkBalance)
                     .flatMap {
                       case Left(e)  => BadRequest(e.toString)
                       case Right(_) => Ok("")
                     }
          } yield resp
      }
  }

  final case class OpenAccountRequest(accountId: AccountId, checkBalance: Boolean)

  implicit val openAccountRequestDecoder =
    io.circe.generic.semiauto.deriveDecoder[OpenAccountRequest]

  def apply[F[_]: Concurrent](service: AccountService[F]): HttpRoutes[F] =
    new Builder(service).routes

}
