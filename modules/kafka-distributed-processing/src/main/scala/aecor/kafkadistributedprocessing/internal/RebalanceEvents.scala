package aecor.kafkadistributedprocessing.internal

import java.util

import aecor.data.Committable
import cats.effect.kernel.{ Async, Deferred }
import cats.effect.std.{ Dispatcher, Queue }
import cats.syntax.all._
import fs2.Stream
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener
import org.apache.kafka.common
import org.apache.kafka.common.TopicPartition

import scala.collection.JavaConverters._

private[kafkadistributedprocessing] object RebalanceEvents {
  final class UsePartiallyApplied[F[_]] {
    def subscribe[A](
      f: ConsumerRebalanceListener => F[Unit]
    )(implicit F: Async[F]): F[Stream[F, Committable[F, RebalanceEvent]]] =
      for {
        queue <- Queue.unbounded[F, Option[Committable[F, RebalanceEvent]]]
        dispatcher <- Dispatcher[F].allocated.map(_._1)
        listener = new Listener[F](
          event =>
            Deferred[F, Unit]
              .flatMap { completion =>
                queue.offer(Committable(completion.complete(()).void, event).some) >> completion.get
            },
          dispatcher
        )
        _ <- f(listener)
      } yield Stream.fromQueueNoneTerminated(queue)
  }

  def apply[F[_]]: UsePartiallyApplied[F] = new UsePartiallyApplied[F]

  sealed abstract class RebalanceEvent
  object RebalanceEvent {
    final case class PartitionsRevoked(partitions: Set[TopicPartition]) extends RebalanceEvent
    final case class PartitionsAssigned(partitions: Set[TopicPartition]) extends RebalanceEvent
  }

  private final class Listener[F[_]: Async](processEvent: RebalanceEvent => F[Unit],
                                            dispatcher: Dispatcher[F])
      extends ConsumerRebalanceListener {

    override def onPartitionsRevoked(partitions: util.Collection[common.TopicPartition]): Unit =
      dispatcher.unsafeRunSync(
        processEvent(RebalanceEvent.PartitionsRevoked(partitions.asScala.toSet))
      )

    override def onPartitionsAssigned(partitions: util.Collection[common.TopicPartition]): Unit =
      dispatcher.unsafeRunSync(
        processEvent(RebalanceEvent.PartitionsAssigned(partitions.asScala.toSet))
      )
  }
}
