package aecor.aggregate
import aecor.data.Folded

import scala.collection.immutable._

trait EventJournal[A, E, F[_]] {
  def append(a: A)(id: String, events: Seq[E]): F[Unit]
  def fold[S](implicit S: Folder[Folded, E, S]): F[Folded[S]]
}
