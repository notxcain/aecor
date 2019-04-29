package aecor.kafkadistributedprocessing

import java.util

import aecor.kafkadistributedprocessing.interop._
import akka.actor.ActorSystem
import akka.kafka.{ ConsumerSettings, Subscriptions }
import akka.stream.{ ActorMaterializer, Materializer }
import cats.effect.concurrent.MVar
import cats.effect.{ Async, ConcurrentEffect, Timer }
import cats.implicits._
import fs2.Stream
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.common.serialization.{ Deserializer, Serializer }

import scala.util.Try

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

  private def populatePartitionCount[F[_]](
    settings: DistributedProcessingSettings,
    consumerRef: MVar[F, Consumer[Unit, Unit]],
    partitionCountRef: MVar[F, Int]
  )(implicit F: Async[F]): F[Unit] =
    partitionCountRef.isEmpty
      .ifM(
        consumerRef.take
          .flatMap { consumer =>
            F.async[Int] { cb =>
              val di = system.dispatchers.lookup(settings.consumerSettings.dispatcher)
              di.execute(new Runnable {
                override def run(): Unit = {
                  val result =
                    Try(consumer.partitionsFor(settings.topicName).size()).toEither
                  cb(result)
                }
              })
            }
          }
          .flatMap(partitionCountRef.put),
        ().pure[F]
      )

  /**
    * Starts `processes` distributed over internal Kafka topic consumers.
    *
    * @param name - used as groupId for underlying Kafka partition assignment machinery
    * @param processes - list of processes to distribute
    *
    */
  def start[F[_]: Timer](
    name: String,
    processes: List[F[Unit]],
    settings: DistributedProcessingSettings = DistributedProcessingSettings.default(system)
  )(implicit F: ConcurrentEffect[F]): F[Unit] =
    Stream
      .eval((MVar[F].empty[Consumer[Unit, Unit]], MVar[F].empty[Int]).tupled)
      .flatMap {
        case (consumerRef, partitionCountRef) =>
          settings.consumerSettings
            .withGroupId(name)
            .withConsumerFactory { cs =>
              val consumer = ConsumerSettings.createKafkaConsumer(cs)
              F.toIO(consumerRef.put(consumer)).unsafeRunAsyncAndForget()
              consumer
            }
            .committablePartitionedStream[F](
              Subscriptions.topics(settings.topicName),
              settings.materializer
            )
            .evalTap { _ =>
              populatePartitionCount(settings, consumerRef, partitionCountRef)
            }
            .evalMap { tp =>
              partitionCountRef.read.map { pc =>
                (assignRange(processes.size, pc, tp.partition), tp.messages)
              }
            }
            .map {
              case ((offset, count), assignedPartition) =>
                println(s"Starting assigned range of processes ($offset, $count)")
                val runProcesses =
                  Stream
                    .iterate(offset)(_ + 1)
                    .take(count.toLong)
                    .covary[F]
                    .parEvalMapUnordered(count) { processNumber =>
                      processes(processNumber)
                    }

                val partitionRevoked = assignedPartition.compile.drain.attempt
                runProcesses.interruptWhen(partitionRevoked)
            }
            .parJoinUnbounded
      }
      .compile
      .drain
}

object DistributedProcessing {
  def apply(system: ActorSystem): DistributedProcessing = new DistributedProcessing(system)
}

final case class DistributedProcessingSettings(topicName: String,
                                               materializer: Materializer,
                                               consumerSettings: ConsumerSettings[Unit, Unit]) {
  def modifyConsumerSettings(
    f: ConsumerSettings[Unit, Unit] => ConsumerSettings[Unit, Unit]
  ): DistributedProcessingSettings =
    DistributedProcessingSettings(topicName, materializer, f(consumerSettings))
  def withTopicName(newTopicName: String): DistributedProcessingSettings =
    copy(topicName = newTopicName)
}

object DistributedProcessingSettings {
  def default(materializer: Materializer,
              consumerSettings: ConsumerSettings[Unit, Unit]): DistributedProcessingSettings =
    DistributedProcessingSettings(
      topicName = "distributed-processing",
      materializer = materializer,
      consumerSettings = consumerSettings
    )

  def default(system: ActorSystem): DistributedProcessingSettings =
    default(ActorMaterializer()(system), ConsumerSettings(system, unitSerde, unitSerde))

  val unitSerde: Serializer[Unit] with Deserializer[Unit] =
    new Serializer[Unit] with Deserializer[Unit] {
      private val emptyByteArray = Array.empty[Byte]
      override def serialize(topic: String, data: Unit): Array[Byte] = emptyByteArray
      override def configure(configs: util.Map[String, _], isKey: Boolean): Unit = ()
      override def deserialize(topic: String, data: Array[Byte]): Unit = ()
      override def close(): Unit = ()
    }
}
