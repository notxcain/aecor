package aecor.schedule.process

import java.util.UUID

import aecor.data.{ Committable, ConsumerId, EventTag }
import aecor.effect.Async.ops._
import aecor.effect.{ Async, Capture }
import aecor.runtime.akkapersistence.CommittableEventJournalQuery
import aecor.schedule.ScheduleEvent
import akka.stream.Materializer
import akka.stream.scaladsl.{ Keep, Sink }
import cats.Applicative
import cats.implicits._

object DefaultScheduleEventJournal {
  def apply[F[_]: Async: Capture: Applicative](
    consumerId: ConsumerId,
    parallelism: Int,
    aggregateJournal: CommittableEventJournalQuery[F, UUID, ScheduleEvent],
    eventTag: EventTag
  )(implicit materializer: Materializer): DefaultScheduleEventJournal[F] =
    new DefaultScheduleEventJournal(consumerId, parallelism, aggregateJournal, eventTag)
}

class DefaultScheduleEventJournal[F[_]: Async: Capture: Applicative](
  consumerId: ConsumerId,
  parallelism: Int,
  aggregateJournal: CommittableEventJournalQuery[F, UUID, ScheduleEvent],
  eventTag: EventTag
)(implicit materializer: Materializer)
    extends ScheduleEventJournal[F] {
  import materializer.executionContext
  override def processNewEvents(f: (ScheduleEvent) => F[Unit]): F[Unit] =
    Capture[F].captureFuture {
      aggregateJournal
        .currentEventsByTag(eventTag, consumerId)
        .mapAsync(parallelism)(_.map(_.event).traverse(f.andThen(_.unsafeRun)))
        .fold(Committable.unit[F])(Keep.right)
        .mapAsync(1)(_.commit.unsafeRun)
        .runWith(Sink.ignore)
    }.void
}
