package aecor.kafkadistributedprocessing

import java.util
import java.util.concurrent.Executors

import aecor.kafkadistributedprocessing.interop._
import akka.actor.ActorSystem
import akka.kafka.{ ConsumerSettings, Subscriptions }
import akka.stream.{ ActorMaterializer, Materializer }
import cats.effect.concurrent.MVar
import cats.effect.{ ConcurrentEffect, ContextShift, Resource, Timer }
import cats.implicits._
import fs2.Stream
import org.apache.kafka.common.serialization.{ Deserializer, Serializer }

import scala.concurrent.ExecutionContext

final class DistributedProcessing private (system: ActorSystem) {

  private def assignRange(size: Int, partitionCount: Int, partition: Int): (Int, Int) = {
    val even = size / partitionCount
    val reminder = size % partitionCount
    if (partition >= partitionCount) {
      (0, 0)
    } else {
      if (partition < reminder) {
        (partition * (even + 1), even + 1)
      } else {
        (reminder + partition * even, even)
      }
    }

  }

  /**
    * Starts `processes` distributed over internal Kafka topic consumers.
    *
    * @param processes - list of processes to distribute
    *
    */
  def start[F[_]: Timer](
    processes: List[F[Unit]],
    settings: DistributedProcessingSettings = DistributedProcessingSettings.default(system),
    materializer: Materializer = ActorMaterializer()(system)
  )(implicit F: ConcurrentEffect[F], contextShift: ContextShift[F]): Resource[F, Unit] =
    Stream
      .eval {
        MVar[F].empty[Int].map { partitionCount =>
          val newSettings = settings.consumerSettings.withConsumerFactory { cs =>
            val consumer = ConsumerSettings.createKafkaConsumer(cs)
            val executor = Executors.newSingleThreadExecutor()
            val blockingEC = ExecutionContext.fromExecutor(executor)
            val updatePartitionCount = contextShift
              .evalOn(blockingEC)(F.delay(consumer.partitionsFor(settings.topicName).size()))
              .map(partitionCount.put)
            F.toIO(updatePartitionCount).unsafeRunSync()
            executor.shutdown()
            consumer
          }
          (partitionCount, newSettings)
        }
      }
      .flatMap {
        case (partitionCount, consumerSettings) =>
          consumerSettings
            .committablePartitionedStream[F](Subscriptions.topics(settings.topicName), materializer)
            .scan(true) { (prefetchPartitionCount, tp) =>
              if (prefetchPartitionCount) {}
            }
            .evalMap { tp =>
              partitionCount.read.map { pc =>
                (assignRange(processes.size, pc, tp.partition), tp.messages)
              }
            }
            .map {
              case ((start, size), messages) =>
                val runProcesses =
                  Stream
                    .iterate(start)(_ + 1)
                    .take(size.toLong)
                    .covary[F]
                    .parEvalMapUnordered(size) { processNumber =>
                      processes(processNumber)
                    }
                runProcesses.interruptWhen(messages.compile.drain.attempt)
            }
            .parJoinUnbounded
      }
      .compile
      .resource
      .drain

}

object DistributedProcessing {
  def apply(system: ActorSystem): DistributedProcessing = new DistributedProcessing(system)
}

final case class DistributedProcessingSettings(topicName: String,
                                               consumerSettings: ConsumerSettings[Unit, Unit]) {
  def modifyConsumerSettings(
    f: ConsumerSettings[Unit, Unit] => ConsumerSettings[Unit, Unit]
  ): DistributedProcessingSettings =
    DistributedProcessingSettings(topicName, f(consumerSettings))
  def withTopicName(newTopicName: String): DistributedProcessingSettings =
    copy(topicName = newTopicName)
}

object DistributedProcessingSettings {
  def default(consumerSettings: ConsumerSettings[Unit, Unit]): DistributedProcessingSettings =
    DistributedProcessingSettings(
      topicName = "distributed-processing",
      consumerSettings = consumerSettings
    )

  def default(system: ActorSystem): DistributedProcessingSettings =
    default(ConsumerSettings(system, unitSerde, unitSerde))

  val unitSerde: Serializer[Unit] with Deserializer[Unit] =
    new Serializer[Unit] with Deserializer[Unit] {
      private val emptyByteArray = Array.empty[Byte]
      override def serialize(topic: String, data: Unit): Array[Byte] = emptyByteArray
      override def configure(configs: util.Map[String, _], isKey: Boolean): Unit = ()
      override def deserialize(topic: String, data: Array[Byte]): Unit = ()
      override def close(): Unit = ()
    }
}
