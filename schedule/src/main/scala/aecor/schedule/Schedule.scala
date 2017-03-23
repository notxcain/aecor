package aecor.schedule

import java.time.{ Clock, LocalDate, LocalDateTime }
import java.util.UUID

import aecor.aggregate.runtime._
import aecor.aggregate.{ CorrelationId, Tagging }
import aecor.data.EventTag
import aecor.streaming._
import akka.NotUsed
import akka.actor.ActorSystem
import akka.persistence.cassandra.journal.CassandraEventJournal
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import cats.implicits._
import cats.{ Functor, Monad, MonadError }

import scala.concurrent.Future
import scala.concurrent.duration._

trait Schedule[F[_]] {
  def addScheduleEntry(scheduleName: String,
                       entryId: String,
                       correlationId: CorrelationId,
                       dueDate: LocalDateTime): F[Unit]
  def committableScheduleEvents(
    scheduleName: String,
    consumerId: ConsumerId
  ): Source[Committable[Future, JournalEntry[UUID, ScheduleEvent]], NotUsed]
}

private[schedule] class ConfiguredSchedule(
  system: ActorSystem,
  entityName: String,
  clock: Clock,
  dayZero: LocalDate,
  bucketLength: FiniteDuration,
  refreshInterval: FiniteDuration,
  eventualConsistencyDelay: FiniteDuration,
  repository: ScheduleEntryRepository,
  aggregateJournal: AggregateJournal[UUID],
  offsetStore: OffsetStore[UUID],
  consumerId: ConsumerId
)(implicit materializer: Materializer) {

  private val runtime = new GenericAkkaRuntime(system)

  private val eventTag = EventTag[ScheduleEvent](entityName)

  private def startAggregate[F[_]: Async: Monad: Capture: CaptureFuture: MonadError[
    ?[_],
    EventsourcedBehavior.BehaviorFailure
  ]]: F[ScheduleAggregate[F]] =
    for {
      journal <- CassandraEventJournal[ScheduleEvent, F](system, 8)
      behavior = EventsourcedBehavior(
        entityName,
        DefaultScheduleAggregate.correlation,
        DefaultScheduleAggregate(clock).asFunctionK,
        Tagging(eventTag),
        journal,
        None,
        NoopSnapshotStore[F, ScheduleState],
        Capture[F].capture(UUID.randomUUID())
      )
      f <- runtime
            .start(entityName, DefaultScheduleAggregate.correlation, behavior)

    } yield ScheduleAggregate.fromFunctionK(f)

  private def startProcess[F[_]: Async: CaptureFuture: Capture: Functor](
    aggregate: ScheduleAggregate[F]
  ) =
    ScheduleProcess[F](
      clock = clock,
      entityName = entityName,
      consumerId = consumerId,
      dayZero = dayZero,
      refreshInterval = refreshInterval,
      eventualConsistencyDelay = eventualConsistencyDelay,
      parallelism = 8,
      offsetStore = offsetStore,
      repository = repository,
      scheduleAggregate = aggregate,
      aggregateJournal = aggregateJournal,
      eventTag = eventTag
    ).run(system)

  private def createSchedule[F[_]](aggregate: ScheduleAggregate[F]): Schedule[F] =
    new DefaultSchedule(clock, aggregate, bucketLength, aggregateJournal, offsetStore, eventTag)

  def start[F[_]: Async: CaptureFuture: Capture: MonadError[?[_],
                                                            EventsourcedBehavior.BehaviorFailure]]
    : F[Schedule[F]] =
    for {
      aggregate <- startAggregate[F]
      _ <- startProcess[F](aggregate)
      schedule = createSchedule[F](aggregate)
    } yield schedule
}

object Schedule {
  def start[F[_]: Async: CaptureFuture: Capture: MonadError[?[_],
                                                            EventsourcedBehavior.BehaviorFailure]](
    entityName: String,
    clock: Clock,
    dayZero: LocalDate,
    bucketLength: FiniteDuration,
    refreshInterval: FiniteDuration,
    eventualConsistencyDelay: FiniteDuration,
    repository: ScheduleEntryRepository,
    aggregateJournal: AggregateJournal[UUID],
    offsetStore: OffsetStore[UUID],
    consumerId: ConsumerId = ConsumerId("io.aecor.schedule.ScheduleProcess")
  )(implicit system: ActorSystem, materializer: Materializer): F[Schedule[F]] =
    new ConfiguredSchedule(
      system,
      entityName,
      clock,
      dayZero,
      bucketLength,
      refreshInterval,
      eventualConsistencyDelay,
      repository,
      aggregateJournal,
      offsetStore,
      consumerId
    ).start[F]
}
