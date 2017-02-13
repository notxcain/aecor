package aecor.streaming

import aecor.aggregate.serialization.PersistentDecoder
import aecor.data.EventTag
import akka.NotUsed
import akka.stream.scaladsl.Source

import scala.concurrent.Future

final case class JournalEntry[+O, +A](offset: O, persistenceId: String, sequenceNr: Long, event: A) {
  def mapOffset[I](f: O => I): JournalEntry[I, A] = copy(f(offset))
}

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
    tag: EventTag[E],
    consumerId: ConsumerId
  ): Source[Committable[JournalEntry[Offset, E]], NotUsed] =
    Source
      .single(NotUsed)
      .mapAsync(1) { _ =>
        offsetStore.getOffset(tag.value, consumerId)
      }
      .flatMapConcat { storedOffset =>
        eventsByTag[E](tag, storedOffset)
          .map(x => Committable(() => offsetStore.setOffset(tag.value, consumerId, x.offset), x))
      }

  def eventsByTag[E: PersistentDecoder](
    tag: EventTag[E],
    offset: Option[Offset]
  ): Source[JournalEntry[Offset, E], NotUsed]

  def currentEventsByTag[E: PersistentDecoder](
    tag: EventTag[E],
    offset: Option[Offset]
  ): Source[JournalEntry[Offset, E], NotUsed]
}
