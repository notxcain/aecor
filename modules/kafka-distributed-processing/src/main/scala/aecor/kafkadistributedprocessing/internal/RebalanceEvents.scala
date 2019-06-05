package aecor.kafkadistributedprocessing.internal

import java.util

import aecor.data.Committable
import cats.effect.concurrent.Deferred
import cats.effect.implicits._
import cats.effect.{ ConcurrentEffect, Effect }
import cats.implicits._
import fs2.Stream
import fs2.concurrent.Queue
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener
import org.apache.kafka.common
import org.apache.kafka.common.TopicPartition

import scala.collection.JavaConverters._

private[kafkadistributedprocessing] object RebalanceEvents {
  final class UsePartiallyApplied[F[_]] {
    def subscribe[A](
      f: ConsumerRebalanceListener => F[Unit]
    )(implicit F: ConcurrentEffect[F]): F[Stream[F, Committable[F, RebalanceEvent]]] =
      for {
        queue <- Queue.unbounded[F, Committable[F, RebalanceEvent]]
        listener = new Listener[F](
          event =>
            Deferred[F, Unit]
              .flatMap { completion =>
                queue.enqueue1(Committable(completion.complete(()), event)) >> completion.get
            }
        )
        _ <- f(listener)
      } yield queue.dequeue
  }

  def apply[F[_]]: UsePartiallyApplied[F] = new UsePartiallyApplied[F]

  sealed abstract class RebalanceEvent
  object RebalanceEvent {
    final case class PartitionsRevoked(partitions: Set[TopicPartition]) extends RebalanceEvent
    final case class PartitionsAssigned(partitions: Set[TopicPartition]) extends RebalanceEvent
  }

  private final class Listener[F[_]: Effect](processEvent: RebalanceEvent => F[Unit])
      extends ConsumerRebalanceListener {

    override def onPartitionsRevoked(partitions: util.Collection[common.TopicPartition]): Unit =
      processEvent(RebalanceEvent.PartitionsRevoked(partitions.asScala.toSet)).toIO
        .unsafeRunSync()

    override def onPartitionsAssigned(partitions: util.Collection[common.TopicPartition]): Unit =
      processEvent(RebalanceEvent.PartitionsAssigned(partitions.asScala.toSet)).toIO
        .unsafeRunSync()
  }
}
