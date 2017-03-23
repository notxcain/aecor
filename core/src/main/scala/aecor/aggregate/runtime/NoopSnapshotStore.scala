package aecor.aggregate.runtime

import cats.Applicative
import cats.implicits._

sealed class NoopSnapshotStore[F[_]: Applicative, S] extends SnapshotStore[F, S] {
  override def saveSnapshot(id: String, state: EventsourcedBehavior.InternalState[S]): F[Unit] =
    ().pure[F]

  override def loadSnapshot(id: String): F[Option[EventsourcedBehavior.InternalState[S]]] =
    none[EventsourcedBehavior.InternalState[S]].pure[F]
}

object NoopSnapshotStore {
  def apply[F[_]: Applicative, S]: NoopSnapshotStore[F, S] = new NoopSnapshotStore[F, S]
}
