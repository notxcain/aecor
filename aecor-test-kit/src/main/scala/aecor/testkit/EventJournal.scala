package aecor.testkit

import aecor.data.Folder
import cats.Monad
import cats.data.NonEmptyVector

trait EventJournal[F[_], I, E] {
  def append(entityId: I, events: NonEmptyVector[E]): F[Unit]
  def foldById[G[_]: Monad, S](entityId: I, offset: Long, folder: Folder[G, E, S]): F[G[S]]
}
