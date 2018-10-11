package aecor.runtime.queue
import cats.effect.Concurrent
import fs2.Stream
import fs2.concurrent.Queue
import cats.implicits._

trait PartitionedQueue[F[_], I, A] {
  def enqueue(a: A): F[Unit]
  def subscribe(consumerId: I): Stream[F, Stream[F, A]]
}

object PartitionedQueue {
  def local[F[_]: Concurrent, I, A]: F[PartitionedQueue[F, I, A]] =
    Queue.unbounded[F, A].map { queue =>
      new PartitionedQueue[F, I, A] {

        override def enqueue(a: A): F[Unit] =
          queue.enqueue1(a)

        override def subscribe(consumerId: I): Stream[F, Stream[F, A]] =
          Stream.emit(queue.dequeue)
      }
    }
}