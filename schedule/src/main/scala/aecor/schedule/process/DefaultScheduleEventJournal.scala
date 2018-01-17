package aecor.schedule.process

import java.util.UUID

import aecor.data.{ Committable, ConsumerId, EventTag, Identified }
import aecor.util._
import aecor.effect.Capture
import aecor.runtime.akkapersistence.CommittableEventJournalQuery
import aecor.schedule.{ ScheduleBucketId, ScheduleEvent }
import akka.stream.Materializer
import akka.stream.scaladsl.{ Keep, Sink }
import cats.Applicative
import cats.effect.Effect
import cats.implicits._

object DefaultScheduleEventJournal {
  def apply[F[_]: Effect: Capture: Applicative](
    consumerId: ConsumerId,
    parallelism: Int,
    aggregateJournal: CommittableEventJournalQuery[F, UUID, ScheduleBucketId, ScheduleEvent],
    eventTag: EventTag
  )(implicit materializer: Materializer): DefaultScheduleEventJournal[F] =
    new DefaultScheduleEventJournal(consumerId, parallelism, aggregateJournal, eventTag)
}

class DefaultScheduleEventJournal[F[_]: Effect: Capture: Applicative](
  consumerId: ConsumerId,
  parallelism: Int,
  aggregateJournal: CommittableEventJournalQuery[F, UUID, ScheduleBucketId, ScheduleEvent],
  eventTag: EventTag
)(implicit materializer: Materializer)
    extends ScheduleEventJournal[F] {
  import materializer.executionContext
  override def processNewEvents(
    f: Identified[ScheduleBucketId, ScheduleEvent] => F[Unit]
  ): F[Unit] =
    Capture[F].captureFuture {
      aggregateJournal
        .currentEventsByTag(eventTag, consumerId)
        .mapAsync(parallelism)(_.map(_.identified).map(f).traverse(_.unsafeToFuture()))
        .fold(Committable.unit[F])(Keep.right)
        .mapAsync(1)(_.commit.unsafeToFuture())
        .runWith(Sink.ignore)
    }.void
}
