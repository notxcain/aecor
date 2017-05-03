package aecor.experimental

import aecor.data.Folder
import cats.Monad
import cats.data.NonEmptyVector

trait EventJournal[F[_], E] {
  def append(id: String, events: NonEmptyVector[E]): F[Unit]
  def foldById[G[_]: Monad, S](id: String, offset: Long, folder: Folder[G, E, S]): F[G[S]]
}
