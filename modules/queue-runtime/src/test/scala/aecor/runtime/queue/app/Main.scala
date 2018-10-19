package aecor.runtime.queue.app
import java.net.InetSocketAddress

import aecor.runtime.queue.Runtime.{ CommandEnvelope, CommandId, CommandResult }
import aecor.runtime.queue._
import aecor.runtime.queue.impl.HelixPartitionedQueue.{ ClusterName, InstanceHost, ZookeeperHost }
import aecor.runtime.queue.impl.KafkaPartitionedQueue.Serialization
import aecor.runtime.queue.impl.{ HelixPartitionedQueue, Http4sClientServer, KafkaPartitionedQueue }
import akka.actor.ActorSystem
import cats.effect.{ ExitCode, IO, IOApp }
import fs2._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.blaze.BlazeBuilder
import scodec._
import codecs._
import cats.implicits._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object Main extends IOApp with Http4sDsl[IO] with CirceEntityEncoder {

  implicit def commandEnvelopeCodec[I, K](implicit I: Codec[I],
                                          K: Codec[K]): Codec[CommandEnvelope[I, K]] =
    (uuid.xmapc[CommandId](CommandId(_))(_.value) :: I :: K :: codecs.bits)
      .as[CommandEnvelope[I, K]]

  implicit val inetSocketAddressCodec: Codec[InetSocketAddress] = (utf8_32 ~ int16).xmap({
    case (host, port) => InetSocketAddress.createUnresolved(host, port)
  }, { x =>
    (x.getHostString, x.getPort)
  })

  def serialization[I](implicit I: Codec[I]): Serialization[String, CommandEnvelope[I, String]] =
    Serialization
      .scodec(utf8_32, commandEnvelopeCodec(I, utf8_32))

  def runOO(args: List[String]): IO[ExitCode] =
    for {
      system <- IO.delay(ActorSystem())
      runtimePort = args.head.toInt
      httpPort = args.tail.head.toInt
      _ = println(httpPort)
      runtime <- Runtime.create[IO](10.seconds, 15.seconds)
      stream = for {
        counters <- {
          val queue = KafkaPartitionedQueue[IO, String, CommandEnvelope[InetSocketAddress, String]](
            system,
            Set("localhost:9092"),
            "counter-commands-40",
            "test-app",
            _.key,
            serialization
          )
          val clientServer = {
            implicit val ec: ExecutionContext = system.dispatcher
            Http4sClientServer[IO, CommandResult](
              new InetSocketAddress("localhost", runtimePort),
              new InetSocketAddress("localhost", runtimePort),
              "counter"
            )
          }
          Stream.resource(runtime.run((_: String) => Counter.inmem[IO], clientServer, queue))
        }
        _ <- Stream.resource {
              val routes = HttpRoutes.of[IO] {
                case GET -> Root / counterId =>
                  for {
                    value <- counters(counterId).value
                    resp <- Ok(value)
                  } yield resp
                case POST -> Root / counterId =>
                  for {
                    value <- counters(counterId).increment
                    resp <- Ok(value)
                  } yield resp
                case DELETE -> Root / counterId =>
                  for {
                    value <- counters(counterId).decrement
                    resp <- Ok(value)
                  } yield resp
              }
              BlazeBuilder[IO]
                .bindHttp(httpPort)
                .mountService(routes, s"/counters")
                .resource
            }
      } yield ()
      _ <- stream.evalMap(_ => IO.never).compile.drain
    } yield ExitCode.Success

  override def run(args: List[String]): IO[ExitCode] =
    for {
      _ <- IO.unit
      httpPort = args.tail.head.toInt
//      isController = args.tail.tail.headOption.contains("controller")
      helix = new HelixPartitionedQueue[IO, Int](
        Set(ZookeeperHost.local),
        ClusterName("Test13"),
        InstanceHost("localhost", httpPort)
      )

      _ <- helix.setup
      _ <- helix.connect.use(_.evalMap(x => IO(println(x)) >> x.complete).compile.drain)
    } yield ExitCode.Success
}
