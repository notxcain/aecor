package aecor.testkit

import aecor.data.Folded
import cats.data.NonEmptyVector

trait EventJournal[F[_], I, E] {
  def append(entityId: I, events: NonEmptyVector[E]): F[Unit]
  def foldById[S](entityId: I, offset: Long, zero: S)(f: (S, E) => Folded[S]): F[Folded[S]]
}
