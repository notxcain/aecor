package aecor.kafkadistributedprocessing

import java.util

import aecor.kafkadistributedprocessing.EnqueueingRebalanceListener.RebalanceCommand
import cats.effect.ConcurrentEffect
import cats.effect.concurrent.Deferred
import fs2.Stream
import fs2.concurrent.Queue
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener
import org.apache.kafka.common
import cats.implicits._
import scala.collection.JavaConverters._

private[kafkadistributedprocessing] final class EnqueueingRebalanceListener[F[_]](
  enqueue: RebalanceCommand[F] => F[Unit]
)(implicit F: ConcurrentEffect[F])
    extends ConsumerRebalanceListener {

  private def enqueueWithCompletionToken[A](f: F[Unit] => RebalanceCommand[F]): F[Unit] =
    Deferred[F, Unit].flatMap(completion => enqueue(f(completion.complete(()))) >> completion.get)

  override def onPartitionsRevoked(partitions: util.Collection[common.TopicPartition]): Unit =
    F.toIO(
        enqueueWithCompletionToken(
          RebalanceCommand.RevokePartitions(partitions.asScala.map(_.partition()).toSet, _)
        )
      )
      .unsafeRunSync()

  override def onPartitionsAssigned(partitions: util.Collection[common.TopicPartition]): Unit =
    F.toIO(
        enqueueWithCompletionToken(
          RebalanceCommand.AssignPartitions(partitions.asScala.map(_.partition()).toSet, _)
        )
      )
      .unsafeRunSync()
}

private[kafkadistributedprocessing] object EnqueueingRebalanceListener {
  final class UsePartiallyApplied[F[_]] {
    def use[A](
      subscribe: ConsumerRebalanceListener => F[A]
    )(implicit F: ConcurrentEffect[F]): F[(A, Stream[F, RebalanceCommand[F]])] =
      for {
        queue <- Queue.unbounded[F, RebalanceCommand[F]]
        listener = new EnqueueingRebalanceListener[F](queue.enqueue1)
        a <- subscribe(listener)
      } yield (a, queue.dequeue)
  }

  def apply[F[_]]: UsePartiallyApplied[F] = new UsePartiallyApplied[F]

  sealed abstract class RebalanceCommand[F[_]]
  object RebalanceCommand {
    final case class RevokePartitions[F[_]](partitions: Set[Int], commit: F[Unit])
        extends RebalanceCommand[F]
    final case class AssignPartitions[F[_]](partitions: Set[Int], commit: F[Unit])
        extends RebalanceCommand[F]
  }
}
