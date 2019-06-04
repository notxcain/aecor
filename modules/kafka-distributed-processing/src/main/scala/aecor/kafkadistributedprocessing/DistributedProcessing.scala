package aecor.kafkadistributedprocessing

import java.util.Properties

import aecor.kafkadistributedprocessing.Kafka._
import cats.effect.{ ConcurrentEffect, ContextShift, Sync, Timer }
import cats.implicits._
import fs2.Stream
import org.apache.kafka.clients.consumer.ConsumerConfig

final class DistributedProcessing(settings: DistributedProcessingSettings) {

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
    * @param name - used as groupId for underlying Kafka partition assignment machinery
    * @param processes - list of processes to distribute
    *
    */
  def start[F[_]: ConcurrentEffect: Timer: ContextShift](name: String,
                                                         processes: List[F[Unit]]): F[Unit] =
    Kafka
      .assignPartitions(settings.asProperties(name), settings.topicName)
      .parEvalMapUnordered(Int.MaxValue) {
        case AssignedPartition(partition, partitionCount, watchRevocation) =>
          val (offset, processCount) = assignRange(processes.size, partitionCount, partition)
          Kafka.runUntilCancelled(watchRevocation) {
            if (processCount > 0)
              Stream
                .range[F](offset, offset + processCount)
                .parEvalMapUnordered(processCount)(processes)
                .compile
                .drain
            else
              ().pure[F]
          }
      }
      .onFinalize(
        Sync[F].delay(
          println(
            s"Terminating process $name, ${settings.consumerSettings.getOrElse(ConsumerConfig.CLIENT_ID_CONFIG, "")}"
          )
        )
      )
      .compile
      .drain
}

object DistributedProcessing {
  def apply(settings: DistributedProcessingSettings): DistributedProcessing =
    new DistributedProcessing(settings)
}

final case class DistributedProcessingSettings(brokers: Set[String],
                                               topicName: String,
                                               consumerSettings: Map[String, String] = Map.empty) {
  def withClientId(clientId: String): DistributedProcessingSettings =
    withConsumerSetting(ConsumerConfig.CLIENT_ID_CONFIG, clientId)

  def withConsumerSetting(key: String, value: String): DistributedProcessingSettings =
    copy(consumerSettings = consumerSettings.updated(key, value))

  def asProperties(groupId: String): Properties = {
    val properties = new Properties()
    consumerSettings.foreach {
      case (key, value) => properties.setProperty(key, value)
    }
    properties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers.mkString(","))
    properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId)
    properties
  }

}
