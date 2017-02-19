package aecor.aggregate.runtime

import aecor.aggregate.SnapshotStore
import cats.Applicative
import cats.implicits._

sealed class NoopSnapshotStore[S, F[_]: Applicative] extends SnapshotStore[S, F] {
  override def saveSnapshot(id: String, state: EventsourcedBehavior.InternalState[S]): F[Unit] =
    ().pure[F]

  override def loadSnapshot(id: String): F[Option[EventsourcedBehavior.InternalState[S]]] =
    none[EventsourcedBehavior.InternalState[S]].pure[F]
}

object NoopSnapshotStore {
  def apply[S, F[_]: Applicative]: NoopSnapshotStore[S, F] = new NoopSnapshotStore[S, F]
}
