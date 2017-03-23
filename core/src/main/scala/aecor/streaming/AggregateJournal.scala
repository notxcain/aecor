package aecor.streaming

import aecor.aggregate.serialization.PersistentDecoder
import aecor.data.EventTag
import akka.NotUsed
import akka.stream.scaladsl.Source

import scala.concurrent.Future

final case class JournalEntry[+O, +A](offset: O, persistenceId: String, sequenceNr: Long, event: A) {
  def mapOffset[I](f: O => I): JournalEntry[I, A] = copy(f(offset))
}

trait AggregateJournal[Offset] {
  def eventsByTag[E: PersistentDecoder](
    tag: EventTag[E],
    offset: Option[Offset]
  ): Source[JournalEntry[Offset, E], NotUsed]

  def currentEventsByTag[E: PersistentDecoder](
    tag: EventTag[E],
    offset: Option[Offset]
  ): Source[JournalEntry[Offset, E], NotUsed]

  final def committableEventsByTag[E: PersistentDecoder](
    offsetStore: OffsetStore[Offset],
    tag: EventTag[E],
    consumerId: ConsumerId
  ): Source[Committable[Future, JournalEntry[Offset, E]], NotUsed] =
    Source
      .single(NotUsed)
      .mapAsync(1) { _ =>
        offsetStore.getOffset(tag.value, consumerId)
      }
      .flatMapConcat { storedOffset =>
        eventsByTag[E](tag, storedOffset)
          .map(x => Committable(() => offsetStore.setOffset(tag.value, consumerId, x.offset), x))
      }

  final def committableCurrentEventsByTag[E: PersistentDecoder](
    offsetStore: OffsetStore[Offset],
    tag: EventTag[E],
    consumerId: ConsumerId
  ): Source[Committable[Future, JournalEntry[Offset, E]], NotUsed] =
    Source
      .single(NotUsed)
      .mapAsync(1) { _ =>
        offsetStore.getOffset(tag.value, consumerId)
      }
      .flatMapConcat { storedOffset =>
        currentEventsByTag(tag, storedOffset)
          .map(x => Committable(() => offsetStore.setOffset(tag.value, consumerId, x.offset), x))
      }
}
