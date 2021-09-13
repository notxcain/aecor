package aecor.runtime.akkapersistence.readside

import aecor.data.{ Committable, ConsumerId, EventTag, TagConsumer }
import aecor.runtime.KeyValueStore
import akka.NotUsed
import akka.stream.scaladsl.Source
import cats.effect.kernel.Async
import cats.effect.std.Dispatcher
import cats.syntax.functor._

final class CommittableEventJournalQuery[F[_]: Async, O, K, E] private[akkapersistence] (
    underlying: JournalQuery[O, K, E],
    offsetStore: KeyValueStore[F, TagConsumer, O],
    dispatcher: Dispatcher[F]
) {
  private def mkCommittableSource(
      tag: EventTag,
      consumerId: ConsumerId,
      inner: Option[O] => Source[JournalEntry[O, K, E], NotUsed]
  ) = {
    val tagConsumerId = TagConsumer(tag, consumerId)
    Source
      .single(NotUsed)
      .mapAsync(1) { _ =>
        dispatcher.unsafeToFuture(offsetStore.getValue(tagConsumerId))
      }
      .flatMapConcat(inner)
      .map(x => Committable(offsetStore.setValue(tagConsumerId, x.offset), x))
  }

  def eventsByTag(
      tag: EventTag,
      consumerId: ConsumerId
  ): Source[Committable[F, JournalEntry[O, K, E]], NotUsed] =
    mkCommittableSource(tag, consumerId, underlying.eventsByTag(tag, _))

  def currentEventsByTag(
      tag: EventTag,
      consumerId: ConsumerId
  ): Source[Committable[F, JournalEntry[O, K, E]], NotUsed] =
    mkCommittableSource(tag, consumerId, underlying.currentEventsByTag(tag, _))
}

private[akkapersistence] object CommittableEventJournalQuery {
  def apply[F[_]: Async, Offset, K, E](
      underlying: JournalQuery[Offset, K, E],
      offsetStore: KeyValueStore[F, TagConsumer, Offset]
  ): F[CommittableEventJournalQuery[F, Offset, K, E]] =
    Dispatcher[F].allocated
      .map(_._1)
      .map(dispatcher => new CommittableEventJournalQuery(underlying, offsetStore, dispatcher))
}
