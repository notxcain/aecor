package aecor.runtime.queue
import cats.effect.implicits._
import cats.effect.{Concurrent, Resource}
import cats.implicits._
import fs2.concurrent.Queue

trait ClientServer[F[_], I, M] {
  def start(f: M => F[Unit]): Resource[F, (I, (I, M) => F[Unit])]
}

object ClientServer {
  def local[F[_]: Concurrent, M]: ClientServer[F, Unit, M] =
    new ClientServer[F, Unit, M] {
      override def start(
        f: M => F[Unit]
      ): Resource[F, (Unit, (Unit, M) => F[Unit])] = Resource.liftF {
        for {
          queue <- Queue.unbounded[F, M]
          _ <- queue.dequeue.evalMap(f).compile.drain.start
        } yield ((), (_: Unit, m: M) => queue.enqueue1(m))
      }
    }
}