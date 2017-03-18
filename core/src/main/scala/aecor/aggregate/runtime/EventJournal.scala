package aecor.aggregate.runtime

import java.util.UUID

import aecor.aggregate.runtime.EventJournal.EventEnvelope
import aecor.data.Folded
import cats.data.NonEmptyVector

import scala.collection.immutable._

trait EventJournal[F[_], E] {
  def append(id: String, instanceId: UUID, events: NonEmptyVector[EventEnvelope[E]]): F[Unit]
  def fold[S](id: String, offset: Long, zero: S, step: (S, E) => Folded[S]): F[Folded[S]]
}

object EventJournal {
  final case class EventEnvelope[E](sequenceNr: Long, event: E, tags: Set[String])
}
