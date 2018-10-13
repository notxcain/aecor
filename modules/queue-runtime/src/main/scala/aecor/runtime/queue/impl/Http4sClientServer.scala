package aecor.runtime.queue.impl

import java.net.InetSocketAddress

import aecor.runtime.queue.ClientServer
import aecor.runtime.queue.ClientServer.Instance
import cats.effect.{ConcurrentEffect, Resource}
import cats.implicits._
import org.http4s.Uri.{Authority, Scheme}
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Server
import org.http4s.server.blaze.BlazeBuilder
import org.http4s.{EntityDecoder, EntityEncoder, HttpRoutes, Method, Request, Uri}
import cats.effect.implicits._
import scala.concurrent.ExecutionContext

final class Http4sClientServer[F[_], A: EntityDecoder[F, ?]: EntityEncoder[F, ?]](
  bindAddress: InetSocketAddress,
  externalAddress: InetSocketAddress,
  path: String
)(implicit F: ConcurrentEffect[F], ec: ExecutionContext)
    extends ClientServer[F, InetSocketAddress, A]
    with Http4sDsl[F] {

  override def start(
    f: A => F[Unit]
  ): Resource[F, Instance[F, InetSocketAddress, A]] =
    for {
      _ <- startServer(f)
      s <- startClient
    } yield Instance(externalAddress, s)

  private def startServer(f: A => F[Unit]): Resource[F, Server[F]] = {
    val routes = HttpRoutes.of[F] {
      case req @ POST -> Root =>
        for {
          a <- req.as[A]
          _ <- f(a)
          resp <- Accepted()
        } yield resp
    }
    BlazeBuilder[F]
      .bindSocketAddress(bindAddress)
      .mountService(routes, s"/$path")
      .resource
  }

  private def startClient: Resource[F, (InetSocketAddress, A) => F[Unit]] = {
    def createResponseSender(client: Client[F]): (InetSocketAddress, A) => F[Unit] = {
      case (address, a) =>
        val uri = Uri(
          Some(Scheme.http),
          Some(Authority(host = Uri.IPv4(address.getHostString), port = Some(address.getPort))),
          path = s"/$path"
        )
        client.fetch(Request[F](Method.POST, uri).withEntity(a))(_ => F.unit).start.void
    }

    BlazeClientBuilder[F](ec).resource
      .flatMap(c => Resource.pure(createResponseSender(c)))
  }
}

object Http4sClientServer {
  def apply[F[_]: ConcurrentEffect, A: EntityDecoder[F, ?]: EntityEncoder[F, ?]](
    bindAddress: InetSocketAddress,
    externalAddress: InetSocketAddress,
    path: String
  )(implicit ec: ExecutionContext): ClientServer[F, InetSocketAddress, A] =
    new Http4sClientServer(bindAddress, externalAddress, path)
}
