package aecor.tests

import aecor.kafkadistributedprocessing.{
  DistributedProcessing,
  DistributedProcessingSettings,
  Supervision
}
import akka.actor.ActorSystem
import cats.effect.{ ExitCode, IO, IOApp, Resource }
import cats.implicits._

import scala.concurrent.duration._

object KafkaDistributedProcessingApp extends IOApp {

  val systemResource: Resource[IO, ActorSystem] =
    Resource.make(IO(ActorSystem("test", TestActorSystem.testConf))) { system =>
      IO.fromFuture(IO(system.terminate())).void
    }

  def process(i: Int): IO[Unit] =
    (fs2.Stream.eval(IO.delay(println(s"${System.currentTimeMillis()} Starting $i"))) ++ fs2.Stream
      .repeatEval(IO.delay(println(i)) >> timer.sleep(1.seconds)))
      .onFinalize(IO.delay(println(s"${System.currentTimeMillis()} Process $i terminated")))
      .compile
      .drain

  override def run(args: List[String]): IO[ExitCode] = IO.suspend {

    val supervision = Supervision.exponentialBackoff[IO]()

    val processes = Stream
      .from(0)
      .take(10)
      .map(process)
      .map(_ >> IO.raiseError(new IllegalStateException("Process terminated")))
      .map(supervision)
      .toList

    systemResource.use { system =>
      val settings = DistributedProcessingSettings
        .default(system)
        .withTopicName("dp3")
        .modifyConsumerSettings(_.withBootstrapServers("localhost:9092"))

      def run(clientId: String) =
        DistributedProcessing(system)
          .start("test", processes, settings.modifyConsumerSettings(_.withClientId(clientId)))

      (run("123"), timer.sleep(10.seconds) >> run("456")).parMapN((_, _) => ExitCode.Success)
    }
  }
}
