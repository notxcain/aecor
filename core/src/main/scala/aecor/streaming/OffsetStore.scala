package aecor.streaming

import java.time.{ Instant, LocalDateTime, ZoneId }
import java.util.UUID

import cats.{ Functor, ~> }
import cats.implicits._
import com.datastax.driver.core.utils.UUIDs
final case class ConsumerId(value: String) extends AnyVal

trait OffsetStore[F[_], A] { outer =>
  def getOffset(tag: String, consumerId: ConsumerId): F[Option[A]]
  def setOffset(tag: String, consumerId: ConsumerId, offset: A): F[Unit]
  def mapK[G[_]](f: F ~> G): OffsetStore[G, A] =
    new OffsetStore[G, A] {
      override def getOffset(tag: String, consumerId: ConsumerId): G[Option[A]] =
        f(outer.getOffset(tag, consumerId))
      override def setOffset(tag: String, consumerId: ConsumerId, offset: A): G[Unit] =
        f(outer.setOffset(tag, consumerId, offset))
    }
  def xmap[B](ab: A => B, ba: B => A)(implicit F: Functor[F]): OffsetStore[F, B] =
    new OffsetStore[F, B] {
      override def getOffset(tag: String, consumerId: ConsumerId): F[Option[B]] =
        outer.getOffset(tag, consumerId).map(_.map(ab))

      override def setOffset(tag: String, consumerId: ConsumerId, offset: B): F[Unit] =
        outer.setOffset(tag, consumerId, ba(offset))
    }
}

object OffsetStore {
  def uuidToLocalDateTime[F[_]: Functor](store: OffsetStore[F, UUID],
                                         zoneId: ZoneId): OffsetStore[F, LocalDateTime] =
    store.xmap(
      uuid => LocalDateTime.ofInstant(Instant.ofEpochMilli(UUIDs.unixTimestamp(uuid)), zoneId),
      value => UUIDs.startOf(value.atZone(zoneId).toInstant.toEpochMilli)
    )
}
