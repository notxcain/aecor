package aecor.runtime.queue.app
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util

import aecor.runtime.queue.Runtime.{CommandEnvelope, CommandResponse}
import akka.actor.ActorSystem
import cats.effect.{ExitCode, IO, IOApp}
import aecor.runtime.queue._
import aecor.runtime.queue.impl.{Http4sClientServer, KafkaPartitionedQueue}
import aecor.runtime.queue.impl.KafkaPartitionedQueue.Serialization
import fs2._
import org.apache.kafka.common.serialization.{Deserializer, Serializer}
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.server.blaze.BlazeBuilder
import org.http4s.circe.CirceEntityEncoder

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object Main extends IOApp with Http4sDsl[IO] with CirceEntityEncoder {
  import boopickle.Default._
  implicit object inetSocketAddressPickler extends Pickler[InetSocketAddress] {
    @inline override def pickle(value: InetSocketAddress)(implicit state: PickleState): Unit = {
      state.enc.writeString(value.getHostString).writeInt(value.getPort)
      ()
    }

    @inline override def unpickle(implicit state: UnpickleState): InetSocketAddress = {
      val host = state.dec.readString
      val port = state.dec.readInt
      InetSocketAddress.createUnresolved(host, port)
    }
  }
  val serialization = Serialization(
    org.apache.kafka.common.serialization.Serdes.String().serializer(),
    org.apache.kafka.common.serialization.Serdes.String().deserializer(),
    new Serializer[CommandEnvelope[InetSocketAddress, String]] {
      override def configure(configs: util.Map[String, _],
                             isKey: Boolean): Unit = ()
      override def serialize(
        topic: String,
        data: CommandEnvelope[InetSocketAddress, String]
      ): Array[Byte] = Pickle.intoBytes(data).array()
      override def close(): Unit = ()
    },
    new Deserializer[CommandEnvelope[InetSocketAddress, String]] {
      override def configure(configs: util.Map[String, _],
                             isKey: Boolean): Unit = ()
      override def deserialize(
        topic: String,
        data: Array[Byte]
      ): CommandEnvelope[InetSocketAddress, String] =
        Unpickle[CommandEnvelope[InetSocketAddress, String]].fromBytes(ByteBuffer.wrap(data))
      override def close(): Unit = ()
    }
  )
  override def run(
    args: List[String]
  ): IO[ExitCode] = for {
    system <- IO.delay(ActorSystem())
    runtimePort = args.head.toInt
    httpPort = args.tail.head.toInt
    _ = println(httpPort)
    runtime <- Runtime.create[IO](10.seconds, 15.seconds)
    queue = KafkaPartitionedQueue[IO, String, CommandEnvelope[InetSocketAddress, String]](system, Set("localhost:9092"), "counter-command-ext", "test-app", _.key, serialization)
    stream = for {
      counters <- {
        val clientServer = {
          implicit val ec: ExecutionContext = system.dispatcher
          Http4sClientServer[IO, CommandResponse](new InetSocketAddress("localhost", runtimePort), new InetSocketAddress("localhost", runtimePort),"counter")
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
}
