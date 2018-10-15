package aecor.runtime.queue
import aecor.runtime.queue.PartitionedQueue.Instance
import cats.effect.{ Concurrent, Resource }
import fs2.Stream
import fs2.concurrent.{ Enqueue, Queue }
import cats.implicits._

trait PartitionedQueue[F[_], A] {
  def start: Resource[F, Instance[F, A]]
}

object PartitionedQueue {
  final case class Instance[F[_], A](enqueue: Enqueue[F, A], dequeue: Stream[F, Stream[F, A]])
  def local[F[_]: Concurrent, A]: PartitionedQueue[F, A] =
    new PartitionedQueue[F, A] {
      override def start: Resource[F, Instance[F, A]] = Resource.liftF {
        Queue.unbounded[F, A].map { queue =>
          Instance(queue, Stream.emit(queue.dequeue))
        }
      }
    }
}
