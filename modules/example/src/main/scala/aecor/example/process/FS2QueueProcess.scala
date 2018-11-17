package aecor.example.process

import aecor.distributedprocessing.DistributedProcessing.{ Process, RunningProcess }
import cats.effect.Concurrent
import cats.effect.concurrent.Deferred
import cats.implicits._
import fs2._
import fs2.concurrent.Queue
import cats.effect.implicits._

object FS2QueueProcess {
  def create[F[_]: Concurrent, A](
    sources: List[Stream[F, A]]
  ): F[(Stream[F, Stream[F, A]], List[Process[F]])] =
    for {
      queue <- Queue.bounded[F, Stream[F, A]](sources.length)
      processes = sources.map { s =>
        Process {
          Deferred[F, Either[Throwable, Unit]].flatMap { stopped =>
            queue
              .enqueue1(s.interruptWhen(stopped))
              .flatTap(_ => stopped.get)
              .start
              .map { fiber =>
                RunningProcess(fiber.join, stopped.complete(Right(())))
              }
          }
        }
      }
    } yield (queue.dequeue, processes)
}
