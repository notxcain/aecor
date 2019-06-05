package aecor.kafkadistributedprocessing

import java.util.Properties

import aecor.kafkadistributedprocessing.internal.Kafka
import aecor.kafkadistributedprocessing.internal.Kafka._
import cats.effect.{ ConcurrentEffect, ContextShift, Timer }
import cats.implicits._
import cats.effect.implicits._
import fs2.Stream
import org.apache.kafka.clients.consumer.ConsumerConfig

import scala.concurrent.duration._

final class DistributedProcessing(settings: DistributedProcessingSettings) {

  private def assignRange(size: Int, partitionCount: Int, partition: Int): Option[(Int, Int)] = {
    val even = size / partitionCount
    val reminder = size % partitionCount
    if (partition >= partitionCount) {
      none
    } else {
      if (partition < reminder) {
        (partition * (even + 1), even + 1).some
      } else if (even > 0) {
        (reminder + partition * even, even).some
      } else none
    }
  }

  /**
    * Starts `processes` distributed over internal Kafka topic consumer.
    *
    * @param name - used as groupId for underlying Kafka partition assignment machinery
    * @param processes - list of processes to distribute
    *
    */
  def start[F[_]: ConcurrentEffect: Timer: ContextShift](name: String,
                                                         processes: List[F[Unit]]): F[Unit] =
    Kafka
      .assignPartitions(
        settings.asProperties(name),
        settings.topicName,
        settings.pollingInterval,
        settings.pollTimeout
      )
      .parEvalMapUnordered(Int.MaxValue) {
        case AssignedPartition(partition, partitionCount, watchRevocation, release) =>
          assignRange(processes.size, partitionCount, partition).fold(release) {
            case (offset, processCount) =>
              Stream
                .range[F](offset, offset + processCount)
                .parEvalMapUnordered(processCount)(processes)
                .compile
                .drain
                .race(watchRevocation)
                .flatMap {
                  case Left(_)         => release
                  case Right(callback) => callback
                }
          }
      }
      .compile
      .drain
}

object DistributedProcessing {
  def apply(settings: DistributedProcessingSettings): DistributedProcessing =
    new DistributedProcessing(settings)
}

final case class DistributedProcessingSettings(brokers: Set[String],
                                               topicName: String,
                                               pollingInterval: FiniteDuration = 500.millis,
                                               pollTimeout: FiniteDuration = 50.millis,
                                               consumerSettings: Map[String, String] = Map.empty) {
  def withClientId(clientId: String): DistributedProcessingSettings =
    withConsumerSetting(ConsumerConfig.CLIENT_ID_CONFIG, clientId)

  def clientId: Option[String] = consumerSettings.get(ConsumerConfig.CLIENT_ID_CONFIG)

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
