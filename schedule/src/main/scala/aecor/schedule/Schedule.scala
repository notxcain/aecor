package aecor.schedule

import java.time.{ Clock, LocalDate, LocalDateTime }
import java.util.UUID

import aecor.aggregate.runtime._
import aecor.aggregate.{ CorrelationId, Tagging }
import aecor.data.EventTag
import aecor.schedule.process.{
  DefaultScheduleProcessOps,
  ScheduleProcess,
  ScheduleProcessRuntime
}
import aecor.streaming._
import akka.NotUsed
import akka.actor.ActorSystem
import akka.persistence.cassandra.journal.CassandraEventJournal
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import cats.MonadError
import cats.implicits._

import scala.concurrent.duration._

trait Schedule[F[_]] {
  def addScheduleEntry(scheduleName: String,
                       entryId: String,
                       correlationId: CorrelationId,
                       dueDate: LocalDateTime): F[Unit]
  def committableScheduleEvents(
    scheduleName: String,
    consumerId: ConsumerId
  ): Source[Committable[F, JournalEntry[UUID, ScheduleEvent]], NotUsed]
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
    repository: ScheduleEntryRepository[F],
    aggregateJournal: AggregateJournal[UUID, ScheduleEvent],
    offsetStore: OffsetStore[F, UUID],
    consumerId: ConsumerId = ConsumerId("io.aecor.schedule.ScheduleProcess")
  )(implicit system: ActorSystem, materializer: Materializer): F[Schedule[F]] = {
    val runtime = new GenericAkkaRuntime(system)

    val eventTag = EventTag[ScheduleEvent](entityName)

    def startAggregate =
      for {
        journal <- CassandraEventJournal[F, ScheduleEvent](system, 8)
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

    def startProcess(aggregate: ScheduleAggregate[F]) = {
      val ops = DefaultScheduleProcessOps[F](
        clock,
        consumerId,
        8,
        offsetStore,
        dayZero,
        repository,
        aggregate,
        aggregateJournal,
        eventTag
      )
      val process = ScheduleProcess(ops, eventualConsistencyDelay, repository, aggregate)
      ScheduleProcessRuntime(entityName, refreshInterval, process).run(system)
    }

    def createSchedule(aggregate: ScheduleAggregate[F]): Schedule[F] =
      new DefaultSchedule(clock, aggregate, bucketLength, aggregateJournal, offsetStore, eventTag)

    for {
      aggregate <- startAggregate
      _ <- startProcess(aggregate)
      schedule = createSchedule(aggregate)
    } yield schedule
  }

}
