package aecor.streaming

import aecor.aggregate.serialization.PersistentDecoder
import akka.NotUsed
import akka.stream.scaladsl.Source

import scala.concurrent.Future

final case class JournalEntry[+O, +A](offset: O, persistenceId: String, sequenceNr: Long, event: A)

object JournalEntry {
  implicit def committable[Offset, A](
    implicit ev: Commit[Offset]
  ): Commit[JournalEntry[Offset, A]] =
    new Commit[JournalEntry[Offset, A]] {
      override def commit(a: JournalEntry[Offset, A]): Future[Unit] = ev.commit(a.offset)
    }
}

trait AggregateJournal[Offset] {
  def committableEventsByTag[E: PersistentDecoder](
    offsetStore: OffsetStore[Offset],
    tag: String
  ): ConsumerId => Source[Committable[JournalEntry[Offset, E]], NotUsed] =
    CommittableSource(offsetStore, tag, eventsByTag(tag))(_.offset)

  def eventsByTag[E: PersistentDecoder](
    tag: String
  ): Option[Offset] => Source[JournalEntry[Offset, E], NotUsed]
}
