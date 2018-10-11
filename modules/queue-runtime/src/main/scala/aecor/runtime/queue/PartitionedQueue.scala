package aecor.runtime.queue
import cats.effect.{Concurrent, Resource}
import fs2.Stream
import fs2.concurrent.{Enqueue, Queue}
import cats.implicits._

trait PartitionedQueue[F[_], A] {
  def start: Resource[F, (Enqueue[F, A], Stream[F, Stream[F, A]])]
}

object PartitionedQueue {
  def local[F[_]: Concurrent, A]: PartitionedQueue[F, A] =
    new PartitionedQueue[F, A] {
      override def start: Resource[F,
        (Enqueue[F, A],
          Stream[F, Stream[F, A]])] = Resource.liftF {
        Queue.unbounded[F, A].map { queue =>
          (queue, Stream.emit(queue.dequeue))
        }
      }
    }

}