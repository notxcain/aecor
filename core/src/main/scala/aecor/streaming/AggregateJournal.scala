package aecor.streaming

import aecor.data.EventTag
import aecor.effect.Async
import Async.ops._
import akka.NotUsed
import akka.stream.scaladsl.Source

final case class JournalEntry[+O, +A](offset: O, persistenceId: String, sequenceNr: Long, event: A) {
  def mapOffset[I](f: O => I): JournalEntry[I, A] = copy(f(offset))
}

trait AggregateJournal[Offset, E] {
  def eventsByTag(tag: EventTag[E],
                  offset: Option[Offset]): Source[JournalEntry[Offset, E], NotUsed]

  def currentEventsByTag(tag: EventTag[E],
                         offset: Option[Offset]): Source[JournalEntry[Offset, E], NotUsed]

  final def committableEventsByTag[F[_]: Async](
    offsetStore: OffsetStore[F, Offset],
    tag: EventTag[E],
    consumerId: ConsumerId
  ): Source[Committable[F, JournalEntry[Offset, E]], NotUsed] =
    Source
      .single(NotUsed)
      .mapAsync(1) { _ =>
        offsetStore.getOffset(tag.value, consumerId).unsafeRun
      }
      .flatMapConcat { storedOffset =>
        eventsByTag(tag, storedOffset)
      }
      .map(x => Committable(() => offsetStore.setOffset(tag.value, consumerId, x.offset), x))

  final def committableCurrentEventsByTag[F[_]: Async](
    offsetStore: OffsetStore[F, Offset],
    tag: EventTag[E],
    consumerId: ConsumerId
  ): Source[Committable[F, JournalEntry[Offset, E]], NotUsed] =
    Source
      .single(NotUsed)
      .mapAsync(1) { _ =>
        offsetStore.getOffset(tag.value, consumerId).unsafeRun
      }
      .flatMapConcat { storedOffset =>
        currentEventsByTag(tag, storedOffset)
      }
      .map(x => Committable(() => offsetStore.setOffset(tag.value, consumerId, x.offset), x))
}
