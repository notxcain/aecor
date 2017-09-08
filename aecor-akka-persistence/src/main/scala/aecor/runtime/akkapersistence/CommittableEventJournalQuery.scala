package aecor.runtime.akkapersistence

import aecor.data.{ Committable, ConsumerId, EventTag, TagConsumer }
import aecor.effect.Async
import aecor.effect.Async.ops._
import aecor.util.KeyValueStore
import akka.NotUsed
import akka.stream.scaladsl.Source

final class CommittableEventJournalQuery[F[_]: Async, O, I, E](
  underlying: EventJournal[O, I, E],
  offsetStore: KeyValueStore[F, TagConsumer, O]
) {

  private def mkCommittableSource(tag: EventTag,
                                  consumerId: ConsumerId,
                                  inner: Option[O] => Source[JournalEntry[O, I, E], NotUsed]) = {
    val tagConsumerId = TagConsumer(tag, consumerId)
    Source
      .single(NotUsed)
      .mapAsync(1) { _ =>
        offsetStore.getValue(tagConsumerId).unsafeRun
      }
      .flatMapConcat(inner)
      .map(x => Committable(offsetStore.setValue(tagConsumerId, x.offset), x))
  }

  def eventsByTag(tag: EventTag,
                  consumerId: ConsumerId): Source[Committable[F, JournalEntry[O, I, E]], NotUsed] =
    mkCommittableSource(tag, consumerId, o => underlying.eventsByTag(tag, o))

  def currentEventsByTag(
    tag: EventTag,
    consumerId: ConsumerId
  ): Source[Committable[F, JournalEntry[O, I, E]], NotUsed] =
    mkCommittableSource(tag, consumerId, o => underlying.currentEventsByTag(tag, o))
}

object CommittableEventJournalQuery {
  def apply[F[_]: Async, Offset, I, E](
    underlying: EventJournal[Offset, I, E],
    offsetStore: KeyValueStore[F, TagConsumer, Offset]
  ): CommittableEventJournalQuery[F, Offset, I, E] =
    new CommittableEventJournalQuery(underlying, offsetStore)
}
