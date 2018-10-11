package aecor.runtime.queue
import aecor.runtime.KeyValueStore
import aecor.runtime.queue.DeferredRegistry.StoreItem
import cats.effect.concurrent.Deferred
import cats.effect.implicits._
import cats.effect.{Concurrent, Timer}
import cats.implicits._

import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

private [queue] class DeferredRegistry[F[_], K, A](timeout: FiniteDuration, kvs: KeyValueStore[F, K, StoreItem[F, A]])(implicit F: Concurrent[F], timer: Timer[F]) {
  def defer(key: K): F[F[A]] =
    for {
      deferred <- Deferred[F, Either[Throwable, A]]
      delete = timer.sleep(timeout) >> deferred.complete(Left(new TimeoutException(timeout.toString))) >> kvs.deleteValue(key)
      fiber <- delete.start
      _ <- kvs.setValue(key, StoreItem[F, A](fiber.cancel, deferred))
    } yield deferred.get.flatMap(F.fromEither)

  def fulfill(key: K, value: A): F[Unit] =
    for {
      pair <- kvs.takeValue(key)
      _ <- pair.traverse_[F, Unit] { case StoreItem(cancelTimeout, deferred) =>
          cancelTimeout >> deferred.complete(Right(value)).attempt.void
      }
    } yield ()
}

private [queue] object DeferredRegistry {
  final case class StoreItem[F[_], A](cancelTimeout: F[Unit], deferred: Deferred[F, Either[Throwable, A]])
  def apply[F[_]: Concurrent: Timer, K, A](timeout: FiniteDuration, kvs: KeyValueStore[F, K, StoreItem[F, A]]): DeferredRegistry[F, K, A] =
    new DeferredRegistry(timeout, kvs)
}