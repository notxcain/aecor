package aecor.runtime

import aecor.data.{ EntityEvent, Folded }
import cats.data.NonEmptyChain
import fs2.Stream

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
  def append(key: K, sequenceNr: Long, events: NonEmptyChain[E]): F[Unit]
  def loadEvents(key: K, offset: Long): Stream[F, EntityEvent[K, E]]
}
