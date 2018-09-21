package aecor.example
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import cats.effect.{IO, LiftIO}

object HttpServer {
  def start[F[_]](route: Route, interface: String, port: Int)(implicit F: LiftIO[F], actorSystem: ActorSystem, materializer: Materializer): F[Http.ServerBinding] =
    F.liftIO(IO.fromFuture(IO(Http().bindAndHandle(route, interface, port))))
}
