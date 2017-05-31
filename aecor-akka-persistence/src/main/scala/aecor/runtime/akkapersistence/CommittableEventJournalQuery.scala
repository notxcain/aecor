package aecor.runtime.akkapersistence

import aecor.data.{ Committable, ConsumerId, EventTag, TagConsumerId }
import aecor.effect.Async
import aecor.util.KeyValueStore
import akka.NotUsed
import akka.stream.scaladsl.Source
import aecor.effect.Async.ops._

class CommittableEventJournalQuery[F[_]: Async, Offset, E](
  underlying: EventJournalQuery[Offset, E],
  offsetStore: KeyValueStore[F, TagConsumerId, Offset]
) {
  final def eventsByTag(
    tag: EventTag[E],
    consumerId: ConsumerId
  ): Source[Committable[F, JournalEntry[Offset, E]], NotUsed] = {
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

  final def currentEventsByTag(
    tag: EventTag[E],
    consumerId: ConsumerId
  ): Source[Committable[F, JournalEntry[Offset, E]], NotUsed] = {
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
