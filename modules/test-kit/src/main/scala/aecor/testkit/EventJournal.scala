package aecor.testkit

import aecor.data.{ ConsumerId, EventTag, Folded, EntityEvent }
import cats.data.NonEmptyVector

trait EventJournal[F[_], I, E] {
  def append(entityId: I, offset: Long, events: NonEmptyVector[E]): F[Unit]
  def foldById[S](entityId: I, offset: Long, zero: S)(f: (S, E) => Folded[S]): F[Folded[S]]
}

trait CurrentEventsByTagQuery[F[_], K, E] {
  def currentEventsByTag(tag: EventTag, consumerId: ConsumerId): Processable[F, EntityEvent[K, E]]
}
