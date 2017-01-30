package aecor.streaming

import akka.NotUsed
import akka.stream.scaladsl.Source

object CommittableSource {
  def apply[O, A, M](offsetStore: OffsetStore[O], tag: String, source: Option[O] => Source[A, M])(
    extractOffset: A => O
  ): ConsumerId => Source[Committable[A], NotUsed] = { consumerId =>
    Source
      .single(NotUsed)
      .mapAsync(1) { _ =>
        offsetStore.getOffset(tag, consumerId)
      }
      .flatMapConcat { storedOffset =>
        source(storedOffset)
          .map { a =>
            Committable(() => offsetStore.setOffset(tag, consumerId, extractOffset(a)), a)
          }
      }
  }
}
