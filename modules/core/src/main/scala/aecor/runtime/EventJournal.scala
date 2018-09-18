package aecor.runtime

import aecor.data.Folded
import cats.data.NonEmptyChain

/**
  * Describes abstract event journal.
  *
  * It is expected that sequence number of the first event is one.
  *
  * @tparam F - effect type
  * @tparam K - entity key type
  * @tparam E - event type
  */
trait EventJournal[F[_], K, E] {
  def append(entityKey: K, sequenceNr: Long, events: NonEmptyChain[E]): F[Unit]
  def foldById[S](entityKey: K, sequenceNr: Long, initial: S)(f: (S, E) => Folded[S]): F[Folded[S]]
}
