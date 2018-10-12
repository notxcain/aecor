package aecor.runtime.queue
import aecor.runtime.queue.ClientServer.Instance
import cats.effect.implicits._
import cats.effect.{Concurrent, Resource}
import cats.implicits._
import fs2.concurrent.Queue

/**
  * A client-server machinery used to delivery command result to the awaiting cluster member
  *
  * @tparam F - effect
  * @tparam I - cluster member identifier
  * @tparam M - message
  */

trait ClientServer[F[_], I, M] {
  def start(f: M => F[Unit]): Resource[F, Instance[F, I, M]]
}

object ClientServer {

  final case class Instance[F[_], I, M](selfId: I, client: (I, M) => F[Unit])

  def local[F[_]: Concurrent, M]: ClientServer[F, Unit, M] =
    new ClientServer[F, Unit, M] {
      override def start(
        f: M => F[Unit]
      ): Resource[F, Instance[F, Unit, M]] = Resource.liftF {
        for {
          queue <- Queue.unbounded[F, M]
          _ <- queue.dequeue.evalMap(f).compile.drain.start
        } yield Instance((), (_: Unit, m: M) => queue.enqueue1(m))
      }
    }
}