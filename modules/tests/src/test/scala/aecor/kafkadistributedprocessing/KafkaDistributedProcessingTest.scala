package aecor.kafkadistributedprocessing

import aecor.tests.IOSupport
import cats.effect.IO
import cats.effect.concurrent.{ Deferred, Ref }
import cats.implicits._
import fs2.concurrent.Queue
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.scalatest.FunSuiteLike

import scala.concurrent.duration._
class KafkaDistributedProcessingTest extends FunSuiteLike with KafkaSupport with IOSupport {

  val topicName = "process-distribution"

  createCustomTopic(topicName, partitions = 4)

  val settings =
    DistributedProcessingSettings(Set(s"localhost:${kafkaConfig.kafkaPort}"), topicName)

  test("Process error propagation") {
    val exception = new RuntimeException("Oops!")

    val result = DistributedProcessing(settings)
      .start("test", List(IO.raiseError[Unit](exception)))
      .attempt
      .timeout(20.seconds)
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

          val run = DistributedProcessing(settings)
            .start("test1", List(process))

          IO.race(run, done.get) >> ref.get
        }
    }

    val (started, finished) = test.timeout(20.seconds).unsafeRunSync()

    assert(started)
    assert(finished)
  }

  test("Process distribution") {
    val test = Queue.unbounded[IO, Int].flatMap { queue =>
      val processes = Stream
        .from(0)
        .take(8)
        .map { idx =>
          queue.enqueue1(idx) >> IO.never.void.guaranteeCase(_ => queue.enqueue1(-idx))
        }
        .toList

      def run(c: String) =
        DistributedProcessing(settings.withConsumerSetting(ConsumerConfig.CLIENT_ID_CONFIG, c))
          .start("test3", processes)

      def dequeue(size: Long): IO[List[Int]] =
        queue.dequeue.take(size).compile.to[List]

      for {
        f1 <- run("1").start
        s1 <- dequeue(8)
        _ = println(s1)
        f2 <- run("2").start
        s2 <- dequeue(8)
        _ = println(s2)
        _ <- f1.cancel
        s3 <- dequeue(8)
        _ = println(s3)
        _ <- f2.cancel
      } yield (s1, s2, List.empty[Int], 0)
    }

    val x @ (s1, s2, s3, tail) = test.timeout(10.seconds).unsafeRunSync()

    println(x)
    assert(s1 == List(0, 1, 2, 3, 4, 5, 6, 7))
    assert(s3 == s1.filter(s2.contains))
  }

}
