package aecor.kafkadistributedprocessing.internal

import aecor.kafkadistributedprocessing.internal
import aecor.kafkadistributedprocessing.internal.Channel.CompletionCallback
import cats.effect.Concurrent
import cats.effect.concurrent.Deferred
import cats.effect.implicits._
import cats.implicits._

private[kafkadistributedprocessing] final case class Channel[F[_]](watch: F[CompletionCallback[F]],
                                                                   close: F[Unit],
                                                                   call: F[Unit])

private[kafkadistributedprocessing] object Channel {
  type CompletionCallback[F[_]] = F[Unit]
  def create[F[_]: Concurrent]: F[Channel[F]] =
    for {
      deferredCallback <- Deferred[F, CompletionCallback[F]]
      closed <- Deferred[F, Unit]
      close = closed.complete(())
      watch = deferredCallback.get
      call = Deferred[F, Unit]
        .flatMap { deferredCompletion =>
          deferredCallback
            .complete(deferredCompletion.complete(()).attempt.void) >> deferredCompletion.get
        }
        .race(closed.get)
        .void

    } yield internal.Channel(watch, close, call)
}
