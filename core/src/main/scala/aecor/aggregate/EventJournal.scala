package aecor.aggregate
import java.util.UUID

import aecor.aggregate.EventJournal.EventEnvelope
import aecor.data.Folded
import cats.data.OneAnd

import scala.collection.immutable._

trait EventJournal[A, E, F[_]] {
  def append(a: A)(id: String, instanceId: UUID, events: OneAnd[Seq, EventEnvelope[E]]): F[Unit]
  def fold[S](a: A)(id: String, offset: Long, zero: Option[S])(
    implicit S: Folder[Folded, E, S]
  ): F[Folded[S]]
}

object EventJournal {
  final case class EventEnvelope[E](sequenceNr: Long, event: E, tags: Set[String])
  object ops {
    implicit class IdOps[A](val self: A) extends AnyVal {
      def append[E, F[_]](
        entityId: String,
        instanceId: UUID,
        events: OneAnd[Seq, EventEnvelope[E]]
      )(implicit A: EventJournal[A, E, F]): F[Unit] =
        A.append(self)(entityId, instanceId, events)
      def fold[S, E, F[_]](id: String, fromSequenceNr: Long, zero: Option[S])(
        implicit A: EventJournal[A, E, F],
        S: Folder[Folded, E, S]
      ): F[Folded[S]] = A.fold(self)(id, fromSequenceNr, zero)
    }
  }
}
