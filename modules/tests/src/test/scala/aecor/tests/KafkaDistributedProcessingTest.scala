package aecor.tests

import aecor.kafkadistributedprocessing.{ DistributedProcessing, DistributedProcessingSettings }
import cats.effect.IO
import cats.effect.concurrent.{ Deferred, Ref }
import cats.implicits._
import fs2.concurrent.Queue
import net.manub.embeddedkafka.{ EmbeddedKafka, EmbeddedKafkaConfig }
import org.scalatest.FunSuiteLike
import scala.concurrent.duration._

class KafkaDistributedProcessingTest extends FunSuiteLike with EmbeddedKafka with IOSupport {

  override protected val topicCreationTimeout: FiniteDuration = 10.seconds

  val embeddedKafka = IO(
    EmbeddedKafka.start()(EmbeddedKafkaConfig(kafkaPort = 0, zooKeeperPort = 0))
  )

  val topicName = "dp4"

//  createCustomTopic("dp", Map.empty, 4)

  val settings = DistributedProcessingSettings(Set(s"localhost:9092"), topicName)

  println("Kafka started")

  test("Process error propagation") {
    val exception = new RuntimeException("Oops!")

    val result = DistributedProcessing(settings)
      .start("test", List(IO.raiseError[Unit](exception)))
      .attempt
      .timeout(80.seconds)
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

    val (started, finished) = test.timeout(80.seconds).unsafeRunSync()

    assert(started)
    assert(finished)
  }

  test("Process distribution") {
    val test = Queue.unbounded[IO, Int].flatMap { queue =>
      val processes = Stream
        .from(0)
        .take(8)
        .map { idx =>
          queue.enqueue1(idx) >> IO.never.void
        }
        .toList

      val run =
        DistributedProcessing(settings)
          .start("test3", processes)

      def dequeue(size: Long): IO[Set[Int]] =
        queue.dequeue.take(size).compile.to[Set]

      for {
        f1 <- run.start
        s1 <- dequeue(8)
        f2 <- run.start
        s2 <- dequeue(4)
        _ <- f1.cancel
        s3 <- dequeue(4)
        _ <- f2.cancel
      } yield (s1, s2, s3)
    }

    val x @ (s1, s2, s3) = test.timeout(80.seconds).unsafeRunSync()
    println(x)
    assert(s1 == Set(0, 1, 2, 3, 4, 5, 6, 7))
    assert(s3 == s1 -- s2)
  }

  override protected def afterAll(): Unit = {
    IO(EmbeddedKafka.stop())
    super.afterAll()
  }
}
