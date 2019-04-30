package aecor.tests

import aecor.kafkadistributedprocessing.{ DistributedProcessing, DistributedProcessingSettings }
import cats.effect.IO
import cats.effect.concurrent.{ Deferred, Ref }
import cats.implicits._
import fs2.concurrent.Queue
import net.manub.embeddedkafka.{ EmbeddedKafka, EmbeddedKafkaConfig }
import org.scalatest.FunSuiteLike

class KafkaDistributedProcessingTest
    extends FunSuiteLike
    with EmbeddedKafka
    with TestActorSystem
    with IOAkkaSpec {

  val embeddedKafka = EmbeddedKafka.start()(EmbeddedKafkaConfig(kafkaPort = 0, zooKeeperPort = 0))

  createCustomTopic("dp", Map.empty, 4, 0)

  val settings = DistributedProcessingSettings
    .default(system)
    .withTopicName("dp")
    .modifyConsumerSettings(_.withBootstrapServers(s"localhost:${embeddedKafka.config.kafkaPort}"))

  println("Kafka started")

  test("Process error propagation") {
    val exception = new RuntimeException("Oops!")

    val result = DistributedProcessing(system)
      .start("test", List(IO.raiseError[Unit](exception)), settings)
      .attempt
      .unsafeRunSync()

    assert(result == Left(exception))
  }

  test("Process lifecycle") {

    val test = Ref.of[IO, (Boolean, Boolean)]((false, false)).flatMap { ref =>
      Deferred[IO, Unit]
        .flatMap { done =>
          val process =
            ref
              .set((true, false))
              .guarantee(ref.set((true, true))) >> done.complete(()) >> IO.never.void

          val run = DistributedProcessing(system)
            .start("test1", List(process), settings)

          IO.race(run, done.get) >> ref.get
        }
    }

    val (started, finished) = test.unsafeRunSync()

    assert(started)
    assert(finished)
  }

  test("Process distribution") {
    val test = Queue.unbounded[IO, Int].flatMap { queue =>
      val processes = Stream
        .from(0)
        .take(8)
        .map { idx =>
          IO(println(s"Starting $idx")) >> queue.enqueue1(idx) >> IO.never.void
        }
        .toList

      def run(clientId: String) =
        DistributedProcessing(system)
          .start("test3", processes, settings.modifyConsumerSettings(_.withClientId(clientId)))

      def dequeue(size: Long): IO[List[Int]] =
        queue.dequeue.evalTap(x => IO(println(x))).take(size).compile.toList

      for {
        f1 <- run("1").start
        _ = println(s"Started 1")
        s1 <- dequeue(8)
        _ = println(s"Dequeued 8")
        f2 <- run("2").start
        _ = println(s"Started 2")
        s2 <- dequeue(4)
        _ = println(s"Dequeued 4")
        _ <- f1.cancel
        s3 <- dequeue(4)
        _ = println(s"Dequeued 4")
        _ <- f2.cancel
      } yield (s1, s2, s3)
    }

    val (s1, s2, s3) = test.unsafeRunSync()

    assert(s1 == List(0, 1, 2, 3, 4, 5, 6, 7))
    assert(s2 == List(4, 5, 6, 7))
    assert(s3 == List(0, 1, 2, 3))
  }

  override protected def afterAll(): Unit = {
    EmbeddedKafka.stop()
    super.afterAll()
  }
}
