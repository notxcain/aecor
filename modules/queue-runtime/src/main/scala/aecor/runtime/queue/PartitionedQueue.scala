package aecor.runtime.queue
import aecor.runtime.queue.PartitionedQueue.Instance
import cats.effect.{ Concurrent, Resource }
import cats.implicits._
import fs2.Stream
import fs2.concurrent.Queue

trait PartitionedQueue[F[_], A] {
  def start: Resource[F, Instance[F, A]]
}

object PartitionedQueue {
  final case class Instance[F[_], A](enqueue: A => F[Unit], dequeue: Stream[F, Stream[F, A]])
  def local[F[_]: Concurrent, A](
    partitionCount: Int
  )(partitioner: A => Int): PartitionedQueue[F, A] =
    new PartitionedQueue[F, A] {
      override def start: Resource[F, Instance[F, A]] = Resource.liftF {
        Queue.unbounded[F, A].replicateA(partitionCount).map { queues =>
          Instance(a => queues(partitioner(a)).enqueue1(a), Stream.emits(queues).map(_.dequeue))
        }
      }
    }
}
