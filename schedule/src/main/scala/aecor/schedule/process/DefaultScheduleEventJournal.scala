package aecor.schedule.process

import java.util.UUID

import aecor.effect.Async.ops._
import aecor.data.{ Committable, EventTag }
import aecor.schedule.ScheduleEvent
import aecor.effect.{ Async, CaptureFuture }
import aecor.runtime.akkapersistence.AggregateJournal
import aecor.streaming._
import akka.stream.Materializer
import akka.stream.scaladsl.{ Keep, Sink }
import cats.Applicative
import cats.implicits._

object DefaultScheduleEventJournal {
  def apply[F[_]: Async: CaptureFuture: Applicative](
    consumerId: ConsumerId,
    parallelism: Int,
    offsetStore: OffsetStore[F, UUID],
    aggregateJournal: AggregateJournal[UUID, ScheduleEvent],
    eventTag: EventTag[ScheduleEvent]
  )(implicit materializer: Materializer): DefaultScheduleEventJournal[F] =
    new DefaultScheduleEventJournal(
      consumerId,
      parallelism,
      offsetStore,
      aggregateJournal,
      eventTag
    )
}

class DefaultScheduleEventJournal[F[_]: Async: CaptureFuture: Applicative](
  consumerId: ConsumerId,
  parallelism: Int,
  offsetStore: OffsetStore[F, UUID],
  aggregateJournal: AggregateJournal[UUID, ScheduleEvent],
  eventTag: EventTag[ScheduleEvent]
)(implicit materializer: Materializer)
    extends ScheduleEventJournal[F] {
  import materializer.executionContext

  override def processNewEvents(f: (ScheduleEvent) => F[Unit]): F[Unit] =
    CaptureFuture[F].captureF {
      aggregateJournal
        .committableCurrentEventsByTag(offsetStore, eventTag, consumerId)
        .mapAsync(parallelism)(_.map(_.event).traverse(f.andThen(_.unsafeRun)))
        .fold(Committable.unit[F])(Keep.right)
        .mapAsync(1)(_.commit().unsafeRun)
        .runWith(Sink.ignore)
    }.void
}
