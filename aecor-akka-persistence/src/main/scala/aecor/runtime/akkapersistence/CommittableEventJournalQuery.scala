package aecor.runtime.akkapersistence

import aecor.data.{ Committable, ConsumerId, EventTag, TagConsumerId }
import aecor.effect.Async
import aecor.util.KeyValueStore
import akka.NotUsed
import akka.stream.scaladsl.Source
import aecor.effect.Async.ops._

final class CommittableEventJournalQuery[F[_]: Async, O, E](
  underlying: EventJournalQuery[O, E],
  offsetStore: KeyValueStore[F, TagConsumerId, O]
) {

  def eventsByTag(tag: EventTag[E],
                  consumerId: ConsumerId): Source[Committable[F, JournalEntry[O, E]], NotUsed] = {
    val tagConsumerId = TagConsumerId(tag.value, consumerId)
    Source
      .single(NotUsed)
      .mapAsync(1) { _ =>
        offsetStore.getValue(tagConsumerId).unsafeRun
      }
      .flatMapConcat { storedOffset =>
        underlying.eventsByTag(tag, storedOffset)
      }
      .map(x => Committable(offsetStore.setValue(tagConsumerId, x.offset), x))
  }

  def currentEventsByTag(
    tag: EventTag[E],
    consumerId: ConsumerId
  ): Source[Committable[F, JournalEntry[O, E]], NotUsed] = {
    val tagConsumerId = TagConsumerId(tag.value, consumerId)
    Source
      .single(NotUsed)
      .mapAsync(1) { _ =>
        offsetStore.getValue(tagConsumerId).unsafeRun
      }
      .flatMapConcat { storedOffset =>
        underlying.currentEventsByTag(tag, storedOffset)
      }
      .map(x => Committable(offsetStore.setValue(tagConsumerId, x.offset), x))
  }
}

object CommittableEventJournalQuery {
  def apply[F[_]: Async, Offset, E](
    underlying: EventJournalQuery[Offset, E],
    offsetStore: KeyValueStore[F, TagConsumerId, Offset]
  ): CommittableEventJournalQuery[F, Offset, E] =
    new CommittableEventJournalQuery(underlying, offsetStore)
}
