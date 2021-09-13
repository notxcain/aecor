package aecor.example.process

import aecor.distributedprocessing.DistributedProcessing.{ Process, RunningProcess }
import cats.effect.Concurrent
import cats.effect.implicits._
import cats.effect.kernel.Deferred
import cats.effect.std.Queue
import cats.implicits._
import fs2._

object FS2QueueProcess {
  def create[F[_]: Concurrent, A](
    sources: List[Stream[F, A]]
  ): F[(Stream[F, Stream[F, A]], List[Process[F]])] =
    for {
      queue <- Queue.bounded[F, Stream[F, A]](sources.length)
      processes = sources.map { s =>
        Process[F] {
          Deferred[F, Either[Throwable, Unit]].flatMap { stopped =>
            queue
              .offer(s.interruptWhen(stopped))
              .flatTap(_ => stopped.get)
              .start
              .map { fiber =>
                RunningProcess(fiber.join.void, stopped.complete(Right(())).void)
              }
          }
        }
      }
    } yield (Stream.fromQueueUnterminated(queue), processes)
}
