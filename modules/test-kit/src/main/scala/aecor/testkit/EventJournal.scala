package aecor.testkit

import aecor.data.{ ConsumerId, EventTag, Folded, EntityEvent }
import cats.data.NonEmptyVector

final case class TaggedEvent[E](event: E, tags: Set[EventTag])

trait EventJournal[F[_], I, E] {
  def append(entityId: I, offset: Long, events: NonEmptyVector[TaggedEvent[E]]): F[Unit]
  def foldById[S](entityId: I, offset: Long, zero: S)(f: (S, E) => Folded[S]): F[Folded[S]]
  def currentEventsByTag(tag: EventTag, consumerId: ConsumerId): Processable[F, EntityEvent[I, E]]
}
