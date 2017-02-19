package aecor.aggregate

import aecor.aggregate.runtime.EventsourcedBehavior.InternalState

trait SnapshotStore[A, S, F[_]] {
  def saveSnapshot(a: A)(id: String, state: InternalState[S]): F[Unit]
  def loadSnapshot(a: A)(id: String): F[Option[InternalState[S]]]
}
