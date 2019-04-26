package aecor.tests

import aecor.kafkadistributedprocessing.{ DistributedProcessing, DistributedProcessingSettings }
import cats.effect.IO
import cats.implicits._

import scala.concurrent.duration._

object KafkaDistributedProcessingTest extends IOAkkaSpec with App {
  val d = DistributedProcessing(system)

  def process(i: Int): IO[Unit] =
    IO.delay(println(i)) >> timer.sleep(1.seconds) >> process(i)

  val settings = DistributedProcessingSettings
    .default(system)
    .withTopicName("dp")
    .modifyConsumerSettings(
      _.withClientId("test").withGroupId("test").withBootstrapServers("localhost:9092")
    )
  d.start(Stream.from(1).take(10).map(process).toList, settings)
    .use(_ => IO.never.void)
    .unsafeRunSync()
}
