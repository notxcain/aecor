package aecor.kafkadistributedprocessing

import cats.effect.concurrent.{ Deferred, Ref }
import cats.effect.{ ExitCase, IO }
import cats.implicits._
import fs2.concurrent.Queue
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.scalatest.funsuite.AnyFunSuiteLike
import scala.concurrent.duration._
class KafkaDistributedProcessingTest extends AnyFunSuiteLike with KafkaSupport with IOSupport {

  val topicName = "process-distribution"

  createCustomTopic(topicName, partitions = 4)

  val settings =
    DistributedProcessingSettings(Set(s"localhost:${kafkaConfig.kafkaPort}"), topicName)

  test("Process error propagation") {
    val exception = new RuntimeException("Oops!")

    val result = DistributedProcessing(settings)
      .start("Process error propagation", List(IO.raiseError[Unit](exception)))
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
            ref.set((true, false)) >>
              done.complete(()) >>
              IO.never.guaranteeCase {
                case ExitCase.Canceled => ref.set((true, true))
                case _                 => IO.unit
              }.void

          val run = DistributedProcessing(settings)
            .start("Process lifecycle", List(process))

          IO.race(run, done.get) >> ref.get
        }
    }

    val (started, finished) = test.timeout(20.seconds).unsafeRunSync()

    assert(started)
    assert(finished)
  }

  test("Process distribution") {
    val test = Queue.unbounded[IO, Int].flatMap { queue =>
      def run(client: Int) =
        DistributedProcessing(
          settings.withConsumerSetting(ConsumerConfig.CLIENT_ID_CONFIG, client.toString)
        ).start(
          "Process distribution",
          Stream
            .from(0)
            .take(8)
            .map { n =>
              val idx = client * 10 + n
              (queue.enqueue1(idx) >> IO.cancelBoundary <* IO.never)
                .guarantee(queue.enqueue1(-idx))
            }
            .toList
        )

      def dequeue(size: Long): IO[List[Int]] =
        queue.dequeue.take(size).compile.to[List]

      for {
        d1 <- run(1).start
        s1 <- dequeue(8)
        d2 <- run(2).start
        s2 <- dequeue(16)
        _ <- d1.cancel
        s3 <- dequeue(16)
        _ <- d2.cancel
        s4 <- dequeue(8)
      } yield (s1, s2, s3, s4)
    }

    val (s1, s2, s3, s4) = test.timeout(20.seconds).unsafeRunSync()

    assert(s1.toSet == Set(10, 11, 12, 13, 14, 15, 16, 17))
    assert((s1 ++ s2 ++ s3 ++ s4).sum == 0)
  }

}
