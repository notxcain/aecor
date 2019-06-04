package aecor.kafkadistributedprocessing

import java.util.Properties

import aecor.kafkadistributedprocessing.Kafka.Channel
import aecor.kafkadistributedprocessing.RebalanceEvents.RebalanceEvent
import aecor.kafkadistributedprocessing.RebalanceEvents.RebalanceEvent.{
  PartitionsAssigned,
  PartitionsRevoked
}
import aecor.tests.IOSupport
import cats.effect.IO
import fs2.concurrent.Queue
import fs2.Stream
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.scalatest.FunSuite
import cats.implicits._
import cats.effect.implicits._

import scala.concurrent.duration._

class KafkaTest extends FunSuite with IOSupport with KafkaSupport {
  val topic = "test"
  val partitionCount = 4

  createCustomTopic(topic, partitions = partitionCount)

  val createConsumerAccess = {
    val properties = new Properties()
    properties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers.mkString(","))
    properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, "test")
    Kafka.createConsumerAccess[IO](properties)
  }

  val watchRebalanceEvents =
    Stream
      .resource(createConsumerAccess)
      .flatMap(Kafka.watchRebalanceEvents(_, topic))

  test("Rebalance event stream") {

    val program = for {
      queue <- Queue.unbounded[IO, (Int, RebalanceEvent)]

      run = (n: Int) =>
        watchRebalanceEvents
          .evalMap { x =>
            val e = n -> x.value
            queue.enqueue1(e) >> x.commit
          }
          .compile
          .drain
          .start

      p1 <- run(1)

      l1 <- queue.dequeue.take(2).compile.toList

      p2 <- run(2)

      l2 <- queue.dequeue.take(4).compile.toList

      _ <- p1.cancel

      l3 <- queue.dequeue.take(2).compile.toList

      _ <- p2.cancel

    } yield (l1, l2, l3)

    val (l1, l2, l3) = program.unsafeRunTimed(40.seconds).get

    def fold(list: List[(Int, RebalanceEvent)]): Map[Int, Set[Int]] =
      list.foldLeft(Map.empty[Int, Set[Int]]) {
        case (s, (c, e)) =>
          e match {
            case PartitionsRevoked(partitions) =>
              s.updated(c, s.getOrElse(c, Set.empty[Int]) -- partitions)
            case PartitionsAssigned(partitions) =>
              s.updated(c, s.getOrElse(c, Set.empty[Int]) ++ partitions)
          }
      }

    assert(fold(l1) == Map(1 -> Set(1, 0, 3, 2)))
    assert(fold(l2) == Map(1 -> Set(1, 0), 2 -> Set(2, 3)))
    assert(fold(l3) == Map(2 -> Set(1, 0, 3, 2)))

  }

  test("Topic partitions query works before subscription") {
    val program = createConsumerAccess.use(_(_.partitionsFor(topic)))
    val result = program.unsafeRunTimed(2.seconds).get
    assert(result.size() == partitionCount)
  }

  test("Channel#call completes only after completion callback") {
    val out = Kafka
      .channel[IO]
      .flatMap {
        case Channel(watch, call) =>
          Queue.unbounded[IO, String].flatMap { queue =>
            for {
              fiber <- watch.flatMap { callback =>
                        queue.enqueue1("before callback") >> callback >> queue
                          .enqueue1("after callback")
                      }.start
              _ <- queue.enqueue1("before call") >> call >> queue.enqueue1("after call")
              _ <- fiber.join
              out <- queue.dequeue.take(4).compile.toList
            } yield out
          }
      }
      .unsafeRunTimed(1.seconds)
      .get
    assert(out == List("before call", "before callback", "after callback", "after call"))
  }

  test("Channel#call does not wait for completion callback if watch cancelled") {
    Kafka
      .channel[IO]
      .flatMap {
        case Channel(watch, call) =>
          watch.start.flatMap(x => x.cancel.race(x.join).map(x => IO(println(x)) >> x.sequence)) >> call
      }
      .unsafeRunTimed(5.seconds)
      .get
    assert(true)
  }
}
