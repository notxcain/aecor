package aecor.aggregate.runtime

import aecor.aggregate.runtime.EventsourcedBehavior.InternalState

trait SnapshotStore[F[_], S] {
  def saveSnapshot(id: String, state: InternalState[S]): F[Unit]
  def loadSnapshot(id: String): F[Option[InternalState[S]]]
}
