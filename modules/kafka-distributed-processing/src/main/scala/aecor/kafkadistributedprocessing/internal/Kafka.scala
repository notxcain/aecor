package aecor.kafkadistributedprocessing.internal

import java.util
import java.util.Properties

import aecor.data.Committable
import aecor.kafkadistributedprocessing.internal.Channel.CompletionCallback
import aecor.kafkadistributedprocessing.internal.RebalanceEvents.RebalanceEvent
import cats.effect.{ ConcurrentEffect, ContextShift, Timer }
import cats.implicits._
import fs2.Stream
import org.apache.kafka.common.serialization.Deserializer

import scala.concurrent.duration._

private[kafkadistributedprocessing] object Kafka {

  type Partition = Int

  final case class AssignedPartition[F[_]](partition: Partition,
                                           partitionCount: Int,
                                           watchRevocation: F[CompletionCallback[F]],
                                           release: F[Unit])

  def watchRebalanceEvents[F[_]: ConcurrentEffect](
    consumer: KafkaConsumer[F, Unit, Unit],
    topic: String,
    pollingInterval: FiniteDuration,
    pollTimeout: FiniteDuration
  )(implicit timer: Timer[F]): Stream[F, Committable[F, RebalanceEvent]] =
    Stream
      .force(
        RebalanceEvents[F]
          .subscribe(consumer.subscribe(Set(topic), _))
          .map(
            _.onFinalize(consumer.unsubscribe)
              .concurrently(
                Stream.repeatEval(consumer.poll(pollTimeout) >> timer.sleep(pollingInterval))
              )
          )
      )

  def assignPartitions[F[_]: ConcurrentEffect: Timer: ContextShift](
    config: Properties,
    topic: String,
    pollingInterval: FiniteDuration,
    pollTimeout: FiniteDuration
  ): Stream[F, AssignedPartition[F]] =
    Stream
      .resource(KafkaConsumer.create[F](config, new UnitDeserializer, new UnitDeserializer))
      .flatMap { consumer =>
        Stream
          .eval(consumer.partitionsFor(topic).map(_.size))
          .flatMap { partitionCount =>
            watchRebalanceEvents(consumer, topic, pollingInterval, pollTimeout)
              .evalScan((List.empty[AssignedPartition[F]], Map.empty[Partition, F[Unit]])) {
                case ((_, revokeTokens), Committable(commit, event)) =>
                  val handleEvent = event match {
                    case RebalanceEvent.PartitionsAssigned(partitions) =>
                      partitions.toList
                        .traverse { partition =>
                          Channel.create[F].map {
                            case Channel(watch, close, call) =>
                              AssignedPartition(partition.partition(), partitionCount, watch, close) -> call
                          }
                        }
                        .map { list =>
                          val assignedPartitions = list.map(_._1)
                          val updatedRevocationCallbacks = revokeTokens ++ list.map {
                            case (AssignedPartition(partition, _, _, _), revoke) =>
                              partition -> revoke
                          }
                          (assignedPartitions, updatedRevocationCallbacks)
                        }
                    case RebalanceEvent.PartitionsRevoked(partitions) =>
                      partitions.toList
                        .map(_.partition())
                        .flatMap(revokeTokens.get)
                        .sequence_
                        .as(
                          (
                            List.empty[AssignedPartition[F]],
                            revokeTokens -- partitions.map(_.partition)
                          )
                        )
                  }
                  handleEvent <* commit
              }
          }
          .flatMap {
            case (assignedPartitions, _) =>
              Stream.emits(assignedPartitions)
          }
      }

  final class UnitDeserializer extends Deserializer[Unit] {
    override def configure(configs: util.Map[String, _], isKey: Boolean): Unit = ()
    override def deserialize(topic: String, data: Array[Byte]): Unit = ()
    override def close(): Unit = ()
  }

}
