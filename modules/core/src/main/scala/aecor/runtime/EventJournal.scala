package aecor.runtime

import aecor.data.Folded
import cats.data.NonEmptyVector

trait EventJournal[F[_], K, E] {
  def append(entityKey: K, sequenceNr: Long, events: NonEmptyVector[E]): F[Unit]
  def foldById[S](entityKey: K, sequenceNr: Long, zero: S)(f: (S, E) => Folded[S]): F[Folded[S]]
}
