package aecor.runtime.akkapersistence.readside

import aecor.data.{ Committable, ConsumerId, EventTag, TagConsumer }
import aecor.runtime.KeyValueStore
import aecor.util.effect._
import akka.NotUsed
import akka.stream.scaladsl.Source
import cats.effect.Effect

final class CommittableEventJournalQuery[F[_]: Effect, O, I, E] private[akkapersistence] (
  underlying: JournalQuery[O, I, E],
  offsetStore: KeyValueStore[F, TagConsumer, O]
) {

  private def mkCommittableSource(tag: EventTag,
                                  consumerId: ConsumerId,
                                  inner: Option[O] => Source[JournalEntry[O, I, E], NotUsed]) = {
    val tagConsumerId = TagConsumer(tag, consumerId)
    Source
      .single(NotUsed)
      .mapAsync(1) { _ =>
        offsetStore.getValue(tagConsumerId).unsafeToFuture()
      }
      .flatMapConcat(inner)
      .map(x => Committable(offsetStore.setValue(tagConsumerId, x.offset), x))
  }

  def eventsByTag(tag: EventTag,
                  consumerId: ConsumerId): Source[Committable[F, JournalEntry[O, I, E]], NotUsed] =
    mkCommittableSource(tag, consumerId, underlying.eventsByTag(tag, _))

  def currentEventsByTag(
    tag: EventTag,
    consumerId: ConsumerId
  ): Source[Committable[F, JournalEntry[O, I, E]], NotUsed] =
    mkCommittableSource(tag, consumerId, underlying.currentEventsByTag(tag, _))
}

private[akkapersistence] object CommittableEventJournalQuery {
  def apply[F[_]: Effect, Offset, I, E](
    underlying: JournalQuery[Offset, I, E],
    offsetStore: KeyValueStore[F, TagConsumer, Offset]
  ): CommittableEventJournalQuery[F, Offset, I, E] =
    new CommittableEventJournalQuery(underlying, offsetStore)
}
