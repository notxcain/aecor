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

object KafkaDistributedProcessingTest extends IOApp {

  val systemResource: Resource[IO, ActorSystem] =
    Resource.make(IO.delay(ActorSystem("test"))) { system =>
      IO.fromFuture(IO(system.terminate())).void
    }

  override def run(args: List[String]): IO[ExitCode] = IO.suspend {

    def process(i: Int): IO[Unit] =
      (fs2.Stream.eval(IO.delay(println(s"Starting $i"))) ++ fs2.Stream
        .repeatEval(IO.delay(println(i)) >> timer.sleep(1.seconds))
        .take(if (i == 0) 5 else Int.MaxValue))
        .as(1)
        .scan1(_ + _)
        .evalMap { x =>
          if (i == 5 && x == 7)
            IO.raiseError[Unit](new RuntimeException("Enough!"))
          else
            IO.unit
        }
        .onFinalize(IO.delay(println(s"Process $i terminated")))
        .compile
        .drain

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
      DistributedProcessing(system)
        .start("test", processes, settings)
        .as(ExitCode.Success)
    }
  }
}
