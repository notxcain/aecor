package aecor.schedule.process

import java.util.UUID

import aecor.data.{ Committable, ConsumerId, EntityEvent, EventTag }
import aecor.runtime.akkapersistence.readside.CommittableEventJournalQuery
import aecor.schedule.{ ScheduleBucketId, ScheduleEvent }
import akka.stream.Materializer
import akka.stream.scaladsl.{ Keep, Sink }
import cats.effect.kernel.Async
import cats.effect.std.Dispatcher
import cats.syntax.all._

object DefaultScheduleEventJournal {
  def apply[F[_]: Async](
      consumerId: ConsumerId,
      parallelism: Int,
      aggregateJournal: CommittableEventJournalQuery[F, UUID, ScheduleBucketId, ScheduleEvent],
      eventTag: EventTag
  )(implicit materializer: Materializer): F[DefaultScheduleEventJournal[F]] =
    Dispatcher[F].allocated
      .map(_._1)
      .map(dispatcher =>
        new DefaultScheduleEventJournal(
          consumerId,
          parallelism,
          aggregateJournal,
          eventTag,
          dispatcher
        )
      )
}

final class DefaultScheduleEventJournal[F[_]: Async](
    consumerId: ConsumerId,
    parallelism: Int,
    aggregateJournal: CommittableEventJournalQuery[F, UUID, ScheduleBucketId, ScheduleEvent],
    eventTag: EventTag,
    dispatcher: Dispatcher[F]
)(implicit materializer: Materializer)
    extends ScheduleEventJournal[F] {
  override def processNewEvents(
      f: EntityEvent[ScheduleBucketId, ScheduleEvent] => F[Unit]
  ): F[Unit] =
    Async[F].fromFuture {
      Async[F].delay {
        aggregateJournal
          .currentEventsByTag(eventTag, consumerId)
          .mapAsync(parallelism)(c => dispatcher.unsafeToFuture(c.map(_.event).traverse(f)))
          .fold(Committable.unit[F])(Keep.right)
          .mapAsync(1)(c => dispatcher.unsafeToFuture(c.commit))
          .runWith(Sink.ignore)
      }
    }.void
}
