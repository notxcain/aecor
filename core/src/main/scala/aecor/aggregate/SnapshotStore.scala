package aecor.aggregate

import aecor.aggregate.runtime.EventsourcedBehavior.InternalState

trait SnapshotStore[S, F[_]] {
  def saveSnapshot(id: String, state: InternalState[S]): F[Unit]
  def loadSnapshot(id: String): F[Option[InternalState[S]]]
}
