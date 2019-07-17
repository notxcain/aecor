package aecor.kafkadistributedprocessing

import java.util.Properties

import aecor.kafkadistributedprocessing.internal.Kafka.UnitDeserializer
import aecor.kafkadistributedprocessing.internal.RebalanceEvents.RebalanceEvent
import aecor.kafkadistributedprocessing.internal.RebalanceEvents.RebalanceEvent.{
  PartitionsAssigned,
  PartitionsRevoked
}
import aecor.kafkadistributedprocessing.internal.{ Kafka, KafkaConsumer }
import cats.effect.IO
import cats.implicits._
import fs2.Stream
import fs2.concurrent.Queue
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration._

class KafkaTest extends AnyFunSuite with IOSupport with KafkaSupport {
  val topic = "test"
  val partitionCount = 4

  createCustomTopic(topic, partitions = partitionCount)

  val createConsumerAccess = {
    val properties = new Properties()
    properties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers.mkString(","))
    properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, "test")
    KafkaConsumer.create[IO](properties, new UnitDeserializer, new UnitDeserializer)
  }

  val watchRebalanceEvents =
    Stream
      .resource(createConsumerAccess)
      .flatMap(Kafka.watchRebalanceEvents(_, topic, 500.millis, 50.millis))

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
              s.updated(c, s.getOrElse(c, Set.empty[Int]) -- partitions.map(_.partition()))
            case PartitionsAssigned(partitions) =>
              s.updated(c, s.getOrElse(c, Set.empty[Int]) ++ partitions.map(_.partition()))
          }
      }

    assert(fold(l1) == Map(1 -> Set(1, 0, 3, 2)))
    assert(fold(l2) == Map(1 -> Set(1, 0), 2 -> Set(2, 3)))
    assert(fold(l3) == Map(2 -> Set(1, 0, 3, 2)))

  }

  test("Topic partitions query works before subscription") {
    val program = createConsumerAccess.use(_.partitionsFor(topic))
    val result = program.unsafeRunTimed(2.seconds).get
    assert(result.size == partitionCount)
  }

}
