package aecor.runtime.queue.impl
import java.util.concurrent.ConcurrentHashMap

import aecor.runtime.queue.DeferredRegistry
import aecor.runtime.queue.impl.ConcurrentHashMapDeferredRegistry.StoreItem
import cats.effect.concurrent.Deferred
import cats.effect.implicits._
import cats.effect.{Concurrent, Timer}
import cats.implicits._

import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

private[queue] final class ConcurrentHashMapDeferredRegistry[F[_], K, A](
  timeout: FiniteDuration,
  store: ConcurrentHashMap[K, StoreItem[F, A]]
)(implicit F: Concurrent[F], timer: Timer[F])
    extends DeferredRegistry[F, K, A] {
  def defer(key: K): F[F[A]] =
    for {
      deferred <- Deferred[F, Either[Throwable, A]]
      delete = timer.sleep(timeout) >> deferred.complete(
        Left(new TimeoutException(timeout.toString))
      ) >> F.delay(store.remove(key))
      fiber <- delete.start
      _ <- F.delay(store.put(key, StoreItem[F, A](fiber.cancel, deferred)))
    } yield deferred.get.flatMap(F.fromEither)

  def fulfill(key: K, value: A): F[Unit] =
    for {
      pair <- F.delay {
               val value = Option(store.get(key))
               store.remove(key)
               value
             }
      _ <- pair.traverse_[F, Unit] {
            case StoreItem(cancelTimeout, deferred) =>
              cancelTimeout >> deferred.complete(Right(value)).attempt.void
          }
    } yield ()
}

object ConcurrentHashMapDeferredRegistry {
  private final case class StoreItem[F[_], A](cancelTimeout: F[Unit],
                                      deferred: Deferred[F, Either[Throwable, A]])
  def create[F[_]: Concurrent: Timer, K, A](
                                             timeout: FiniteDuration
                                           ): F[DeferredRegistry[F, K, A]] =
    Concurrent[F].delay(
      new ConcurrentHashMapDeferredRegistry(timeout, new ConcurrentHashMap[K, StoreItem[F, A]]())
    )
}